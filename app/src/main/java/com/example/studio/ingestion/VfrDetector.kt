package com.example.studio.ingestion

import java.io.File
import java.util.Locale
import kotlin.math.abs

data class VfrAnalysisReport(
    val sampleCount: Int,
    val nominalFps: Float,
    val medianDeltaUs: Long,
    val minDeltaUs: Long,
    val maxDeltaUs: Long,
    val maxJitterRatio: Float,
    val irregularIntervalCount: Int,
    val nonMonotonicOrDuplicateCount: Int,
    val isVfr: Boolean,
    val boundedPtsDeltasUs: LongArray,
    val scanComplete: Boolean = true,
    val definitiveIsVfr: Boolean? = if (scanComplete) isVfr else null
) {
    /**
     * Serializes the bounded delta-encoded PTS manifest to a compact text format:
     * Header line: `v1|fps=<nominalFps>|medianUs=<medianDeltaUs>|vfr=<definitiveIsVfr>|scanComplete=<scanComplete>|count=<N>`
     * Body line: comma-separated delta microseconds (`d0,d1,d2,...`) capped at [VfrDetector.MAX_PTS_MANIFEST_SAMPLES].
     */
    fun serializeCompactManifest(): String {
        val sb = StringBuilder(boundedPtsDeltasUs.size * 6 + 80)
        sb.append("v1|fps=").append(String.format(Locale.US, "%.3f", nominalFps))
            .append("|medianUs=").append(medianDeltaUs)
            .append("|vfr=").append(definitiveIsVfr?.toString() ?: "approximate")
            .append("|scanComplete=").append(scanComplete)
            .append("|nonMono=").append(nonMonotonicOrDuplicateCount)
            .append("|count=").append(boundedPtsDeltasUs.size)
            .append('\n')
        for (i in boundedPtsDeltasUs.indices) {
            if (i > 0) sb.append(',')
            sb.append(boundedPtsDeltasUs[i])
        }
        return sb.toString()
    }
}

object VfrDetector {

    /**
     * Maximum number of frame intervals stored in a `.pts` manifest file (3,600 samples =
     * 150 seconds at 24fps, keeping every `.pts` file strictly under 32 KB on disk).
     */
    const val MAX_PTS_MANIFEST_SAMPLES = 3_600

    /**
     * Default safety ceiling when callers request a bounded scan.
     */
    const val MAX_EXTRACTOR_SCAN_SAMPLES = 7_200

    /**
     * Frame interval deviation threshold (15% from median interval) above which a frame step
     * is counted as an irregular presentation interval.
     */
    const val JITTER_THRESHOLD_RATIO = 0.15f

    /**
     * Extracts presentation timestamps from the first video track of [videoFile] using
     * [MediaExtractorProbe] without loading the file or decoded bitmaps into memory.
     */
    fun analyzeVideoFile(
        videoFile: File,
        maxSamplesToScan: Long = Long.MAX_VALUE,
        maxPtsEntries: Int = MAX_PTS_MANIFEST_SAMPLES + 1
    ): VfrAnalysisReport {
        val probe = MediaExtractorProbe.inspect(
            file = videoFile,
            maxSamplesToScan = maxSamplesToScan,
            maxPtsEntries = maxPtsEntries
        )
        if (!probe.hasVideoTrack) {
            return analyzePresentationTimestamps(
                rawPtsUs = emptyList(),
                fallbackFps = 24f,
                scanComplete = probe.scanComplete
            )
        }
        val baseReport = analyzePresentationTimestamps(
            rawPtsUs = probe.sampledPtsUs,
            fallbackFps = (probe.nominalFrameRate ?: 24).toFloat(),
            scanComplete = probe.scanComplete
        )
        val combinedVfr = baseReport.isVfr || (probe.isVfr == true)
        return baseReport.copy(
            sampleCount = probe.totalSamplesScanned.toInt().coerceAtLeast(baseReport.sampleCount),
            nonMonotonicOrDuplicateCount = maxOf(
                baseReport.nonMonotonicOrDuplicateCount,
                probe.nonMonotonicOrDuplicateCount
            ),
            isVfr = combinedVfr,
            scanComplete = probe.scanComplete,
            definitiveIsVfr = if (probe.scanComplete) combinedVfr else null
        )
    }

    /**
     * Pure deterministic analyzer over bounded presentation timestamps (in microseconds).
     */
    fun analyzePresentationTimestamps(
        rawPtsUs: List<Long>,
        fallbackFps: Float = 24f,
        scanComplete: Boolean = true
    ): VfrAnalysisReport {
        val safeFallbackFps = if (fallbackFps in 1f..240f) fallbackFps else 24f
        if (rawPtsUs.size < 2) {
            val defaultDelta = (1_000_000f / safeFallbackFps).toLong()
            return VfrAnalysisReport(
                sampleCount = rawPtsUs.size,
                nominalFps = safeFallbackFps,
                medianDeltaUs = defaultDelta,
                minDeltaUs = defaultDelta,
                maxDeltaUs = defaultDelta,
                maxJitterRatio = 0f,
                irregularIntervalCount = 0,
                nonMonotonicOrDuplicateCount = 0,
                isVfr = false,
                boundedPtsDeltasUs = LongArray(0),
                scanComplete = scanComplete,
                definitiveIsVfr = if (scanComplete) false else null
            )
        }

        var nonMonotonicOrDuplicateCount = 0
        for (i in 1 until rawPtsUs.size) {
            if (rawPtsUs[i] <= rawPtsUs[i - 1]) {
                nonMonotonicOrDuplicateCount++
            }
        }

        val sortedPts = rawPtsUs.sorted()
        var duplicatePresentationPtsCount = 0
        val positiveDeltas = ArrayList<Long>(sortedPts.size - 1)
        val boundedDeltas = LongArray(minOf(sortedPts.size - 1, MAX_PTS_MANIFEST_SAMPLES))

        for (i in 1 until sortedPts.size) {
            val delta = sortedPts[i] - sortedPts[i - 1]
            if (i - 1 < boundedDeltas.size) {
                boundedDeltas[i - 1] = delta
            }
            if (delta <= 0L) {
                duplicatePresentationPtsCount++
            } else {
                positiveDeltas.add(delta)
            }
        }

        if (positiveDeltas.isEmpty()) {
            val defaultDelta = (1_000_000f / safeFallbackFps).toLong()
            return VfrAnalysisReport(
                sampleCount = rawPtsUs.size,
                nominalFps = safeFallbackFps,
                medianDeltaUs = defaultDelta,
                minDeltaUs = 0L,
                maxDeltaUs = 0L,
                maxJitterRatio = 1f,
                irregularIntervalCount = sortedPts.size - 1,
                nonMonotonicOrDuplicateCount = maxOf(nonMonotonicOrDuplicateCount, duplicatePresentationPtsCount),
                isVfr = true,
                boundedPtsDeltasUs = boundedDeltas,
                scanComplete = scanComplete,
                definitiveIsVfr = if (scanComplete) true else null
            )
        }

        val sortedDeltas = positiveDeltas.sorted()
        val medianDeltaUs = sortedDeltas[sortedDeltas.size / 2].coerceAtLeast(1L)
        val minDeltaUs = sortedDeltas.first()
        val maxDeltaUs = sortedDeltas.last()
        val nominalFps = (1_000_000f / medianDeltaUs.toFloat()).coerceIn(1f, 240f)

        var maxJitterRatio = 0f
        var irregularIntervalCount = 0
        for (delta in positiveDeltas) {
            val jitter = abs(delta - medianDeltaUs).toFloat() / medianDeltaUs.toFloat()
            if (jitter > maxJitterRatio) {
                maxJitterRatio = jitter
            }
            if (jitter > JITTER_THRESHOLD_RATIO) {
                irregularIntervalCount++
            }
        }

        val isVfr = duplicatePresentationPtsCount > 0 ||
            nonMonotonicOrDuplicateCount > 0 ||
            irregularIntervalCount > 0 ||
            maxJitterRatio > JITTER_THRESHOLD_RATIO

        return VfrAnalysisReport(
            sampleCount = rawPtsUs.size,
            nominalFps = nominalFps,
            medianDeltaUs = medianDeltaUs,
            minDeltaUs = minDeltaUs,
            maxDeltaUs = maxDeltaUs,
            maxJitterRatio = maxJitterRatio,
            irregularIntervalCount = irregularIntervalCount,
            nonMonotonicOrDuplicateCount = maxOf(nonMonotonicOrDuplicateCount, duplicatePresentationPtsCount),
            isVfr = isVfr,
            boundedPtsDeltasUs = boundedDeltas,
            scanComplete = scanComplete,
            definitiveIsVfr = if (scanComplete) isVfr else null
        )
    }

    /**
     * Writes the compact `.pts` manifest to [metadataDir]/`[assetId].pts`.
     */
    fun writePtsManifestFile(
        metadataDir: File,
        assetId: String,
        report: VfrAnalysisReport
    ): File {
        metadataDir.mkdirs()
        val safeId = assetId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val ptsFile = File(metadataDir, "$safeId.pts")
        ptsFile.writeText(report.serializeCompactManifest(), Charsets.UTF_8)
        return ptsFile
    }
}
