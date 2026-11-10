package com.example.studio.ingestion

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Bounded-memory video-track probe using Android [MediaExtractor].
 *
 * `MediaExtractor.sampleTime` is a presentation timestamp in microseconds.
 * This probe scans incrementally and retains no more than [maxPtsEntries] timestamps.
 * A complete scan is O(number of samples) time and O(maxPtsEntries) memory — never
 * reading the entire file into a byte array.
 *
 * A truncated scan (`scanComplete == false`) is reported with `isVfr = null` (approximate)
 * and never claims definitive VFR/CFR status.
 */
data class ExtractorProbeResult(
    val hasVideoTrack: Boolean,
    val mime: String?,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val nominalFrameRate: Int?,
    val isVfr: Boolean?,
    val scanComplete: Boolean,
    val sampledPtsUs: List<Long>,
    val totalSamplesScanned: Long = 0L,
    val nonMonotonicOrDuplicateCount: Int = 0,
    val error: String? = null
)

object MediaExtractorProbe {

    const val DEFAULT_MAX_PTS_ENTRIES = 3_600

    fun inspect(
        file: File,
        maxSamplesToScan: Long = Long.MAX_VALUE,
        maxPtsEntries: Int = DEFAULT_MAX_PTS_ENTRIES
    ): ExtractorProbeResult {
        require(maxSamplesToScan > 0L) { "maxSamplesToScan must be > 0" }
        require(maxPtsEntries >= 2) { "maxPtsEntries must be >= 2" }

        if (!file.isFile || file.length() <= 0L) {
            return ExtractorProbeResult(
                hasVideoTrack = false,
                mime = null,
                width = 0,
                height = 0,
                durationUs = 0L,
                nominalFrameRate = null,
                isVfr = null,
                scanComplete = true,
                sampledPtsUs = emptyList(),
                error = "File missing/empty"
            )
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrack < 0 || videoFormat == null) {
                return ExtractorProbeResult(
                    hasVideoTrack = false,
                    mime = null,
                    width = 0,
                    height = 0,
                    durationUs = 0L,
                    nominalFrameRate = null,
                    isVfr = null,
                    scanComplete = true,
                    sampledPtsUs = emptyList(),
                    error = "No video track"
                )
            }

            val format = videoFormat
            val mime = format.getString(MediaFormat.KEY_MIME)
            val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                format.getInteger(MediaFormat.KEY_WIDTH)
            } else {
                0
            }
            val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                format.getInteger(MediaFormat.KEY_HEIGHT)
            } else {
                0
            }
            var durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } catch (_: Exception) {
                    try {
                        format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                null
            }

            extractor.selectTrack(videoTrack)

            val pts = ArrayList<Long>(minOf(maxPtsEntries, 4096))
            var previousPts = Long.MIN_VALUE
            var firstPts = Long.MIN_VALUE
            var maxPts = Long.MIN_VALUE
            var firstDelta: Long? = null
            var vfr = false
            var nonMonoCount = 0
            var samples = 0L
            var scanComplete = false

            val strideDivisor = if (maxSamplesToScan == Long.MAX_VALUE) {
                1L
            } else {
                maxOf(1L, maxSamplesToScan / maxPtsEntries)
            }

            while (samples < maxSamplesToScan) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) {
                    scanComplete = true
                    break
                }

                if (firstPts == Long.MIN_VALUE) {
                    firstPts = sampleTimeUs
                }
                if (sampleTimeUs > maxPts) {
                    maxPts = sampleTimeUs
                }

                if (previousPts != Long.MIN_VALUE) {
                    val delta = sampleTimeUs - previousPts
                    if (delta <= 0L) {
                        nonMonoCount++
                        vfr = true
                    } else {
                        val baseline = firstDelta
                        if (baseline == null) {
                            firstDelta = delta
                        } else {
                            // Cadence variance tolerance: 2% or 1 ms (1,000 us)
                            val tolerance = max(1_000L, (baseline * 0.02).toLong())
                            if (abs(delta - baseline) > tolerance) {
                                vfr = true
                            }
                        }
                    }
                }

                // Sparse, bounded timeline samples; never accumulate unbounded frame PTS in memory
                if (pts.size < maxPtsEntries) {
                    pts += sampleTimeUs
                } else if (samples % strideDivisor == 0L) {
                    val slot = ((samples / strideDivisor) % maxPtsEntries).toInt()
                    pts[slot] = sampleTimeUs
                }

                previousPts = sampleTimeUs
                samples++

                if (!extractor.advance()) {
                    scanComplete = true
                    break
                }
            }

            // If we hit maxSamplesToScan, peek whether the stream actually ended on this boundary
            if (!scanComplete && extractor.sampleTime < 0L) {
                scanComplete = true
            }

            if (durationUs <= 0L && firstPts != Long.MIN_VALUE && maxPts > firstPts) {
                durationUs = maxPts - firstPts
            }

            val derivedFps = fps ?: if (durationUs > 0L && samples >= 2L) {
                (((samples - 1L) * 1_000_000L) / durationUs).toInt().coerceIn(1, 240)
            } else {
                null
            }

            return ExtractorProbeResult(
                hasVideoTrack = true,
                mime = mime,
                width = width,
                height = height,
                durationUs = durationUs,
                nominalFrameRate = derivedFps,
                isVfr = if (scanComplete) vfr else null,
                scanComplete = scanComplete,
                sampledPtsUs = pts,
                totalSamplesScanned = samples,
                nonMonotonicOrDuplicateCount = nonMonoCount,
                error = null
            )
        } catch (e: Exception) {
            return ExtractorProbeResult(
                hasVideoTrack = false,
                mime = null,
                width = 0,
                height = 0,
                durationUs = 0L,
                nominalFrameRate = null,
                isVfr = null,
                scanComplete = false,
                sampledPtsUs = emptyList(),
                error = e.message ?: "MediaExtractor failed"
            )
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
