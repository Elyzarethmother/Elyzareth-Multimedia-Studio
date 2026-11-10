package com.example.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.model.AudioSpectrumTimeline
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "AudioPcmAnalyzer"

/**
 * Decodes real audio files (MP3, WAV, AAC, M4A, OGG) into PCM samples using MediaExtractor + MediaCodec
 * and computes time-indexed 24-band frequency amplitudes via Goertzel / DFT spectral analysis.
 * Ensures both live Preview and HD MP4 Export drive their visualizers from actual audio waveform data.
 */
object AudioPcmAnalyzer {

    const val VISUALIZER_FPS = 24
    const val NUM_BANDS = 24

    // 25 logarithmic boundary frequencies (in Hz) defining 24 contiguous bands from 50 Hz to 10,000 Hz
    private val bandBoundaryFrequenciesHz: DoubleArray = DoubleArray(NUM_BANDS + 1) { idx ->
        val minFreq = 50.0
        val maxFreq = 10000.0
        val ratio = idx.toDouble() / NUM_BANDS.toDouble()
        minFreq * Math.pow(maxFreq / minFreq, ratio)
    }

    /**
     * Analyzes a mono 16-bit PCM array at [sampleRate] and generates a 24-band [AudioSpectrumTimeline]
     * at [framesPerSecond] (default 24fps).
     */
    fun analyzePcmSamples(
        pcmSamples: ShortArray,
        sampleRate: Int = SynthAudioGenerator.SAMPLE_RATE,
        framesPerSecond: Int = VISUALIZER_FPS
    ): AudioSpectrumTimeline {
        if (pcmSamples.isEmpty() || sampleRate <= 0) {
            return AudioSpectrumTimeline(
                framesPerSecond = framesPerSecond,
                durationSec = 0f,
                frames = emptyList(),
                isFromDecodedPcm = false
            )
        }

        val samplesPerFrame = (sampleRate / framesPerSecond).coerceAtLeast(256)
        val totalFrames = ((pcmSamples.size + samplesPerFrame - 1) / samplesPerFrame).coerceAtLeast(1)
        val windowSize = minOf(512, samplesPerFrame)
        val frames = ArrayList<FloatArray>(totalFrames)

        for (frameIdx in 0 until totalFrames) {
            val startSample = frameIdx * samplesPerFrame
            val available = (pcmSamples.size - startSample).coerceAtMost(windowSize)
            if (available <= 16) {
                frames.add(FloatArray(NUM_BANDS) { 0.04f })
                continue
            }
            frames.add(
                compute24BandSpectrumFromPcmWindow(
                    pcm = pcmSamples,
                    offset = startSample,
                    length = available,
                    sampleRate = sampleRate
                )
            )
        }

        val durationSec = pcmSamples.size.toFloat() / sampleRate.toFloat()
        return AudioSpectrumTimeline(
            framesPerSecond = framesPerSecond,
            durationSec = durationSec,
            frames = frames,
            isFromDecodedPcm = true
        )
    }

    /**
     * Computes 24 frequency-band amplitudes (0.04f..1.0f) for a window of 16-bit mono PCM samples
     * using the Goertzel algorithm across 24 contiguous logarithmic frequency bands.
     */
    fun compute24BandSpectrumFromPcmWindow(
        pcm: ShortArray,
        offset: Int,
        length: Int,
        sampleRate: Int
    ): FloatArray {
        val bands = FloatArray(NUM_BANDS)
        val n = length.coerceAtMost(pcm.size - offset)
        if (n <= 0) return FloatArray(NUM_BANDS) { 0.04f }

        // Pre-apply Hann window and normalize to -1.0..1.0
        val windowed = DoubleArray(n)
        for (i in 0 until n) {
            val sampleNorm = pcm[offset + i].toDouble() / 32768.0
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1).coerceAtLeast(1)))
            windowed[i] = sampleNorm * hann
        }

        for (b in 0 until NUM_BANDS) {
            val fLow = bandBoundaryFrequenciesHz[b].coerceAtMost(sampleRate * 0.45)
            val fHigh = bandBoundaryFrequenciesHz[b + 1].coerceAtMost(sampleRate * 0.45)
            val kLow = ((n * fLow) / sampleRate).toInt().coerceAtLeast(1)
            val kHigh = (0.99 + (n * fHigh) / sampleRate).toInt().coerceAtLeast(kLow)

            var maxBandMagnitude = 0.0
            for (k in kLow..kHigh) {
                val omega = (2.0 * PI * k) / n
                val coeff = 2.0 * cos(omega)
                var q0: Double
                var q1 = 0.0
                var q2 = 0.0

                for (i in 0 until n) {
                    q0 = coeff * q1 - q2 + windowed[i]
                    q2 = q1
                    q1 = q0
                }

                val magnitudeSquared = q1 * q1 + q2 * q2 - q1 * q2 * coeff
                val mag = sqrt(magnitudeSquared.coerceAtLeast(0.0)) / (n * 0.25)
                if (mag > maxBandMagnitude) {
                    maxBandMagnitude = mag
                }
            }

            // High-frequency perceptual tilt compensation
            val tiltBoost = 1.0 + (b.toDouble() / NUM_BANDS) * 1.4
            val scaled = (maxBandMagnitude * tiltBoost * 2.0).coerceIn(0.04, 1.0)
            bands[b] = scaled.toFloat()
        }

        return bands
    }

    /**
     * Streams and decodes an imported audio URI (MP3/M4A/WAV/OGG) via MediaExtractor and MediaCodec
     * in bounded memory chunks, computing the complete [AudioSpectrumTimeline] without loading the
     * entire uncompressed PCM wave into memory at once.
     */
    fun decodeAndAnalyzeUri(
        context: Context,
        audioUri: Uri,
        maxDurationSec: Int = 600,
        framesPerSecond: Int = VISUALIZER_FPS
    ): Result<AudioSpectrumTimeline> {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        return try {
            extractor = MediaExtractor().apply {
                setDataSource(context, audioUri, null)
            }

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            var mimeType = ""
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    mimeType = mime
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                return Result.failure(IllegalArgumentException("No decodable audio track found in URI."))
            }

            extractor.selectTrack(audioTrackIndex)
            val codec = MediaCodec.createDecoderByType(mimeType)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }

            var samplesPerFrame = (sampleRate / framesPerSecond).coerceAtLeast(256)
            var currentFrameBuffer = ShortArray(samplesPerFrame)
            var currentFrameCursor = 0

            val spectrumFrames = ArrayList<FloatArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            val maxFrames = maxDurationSec * framesPerSecond
            var totalMonoSamplesDecoded = 0L

            while (!outputEos && spectrumFrames.size < maxFrames) {
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFmt = codec.outputFormat
                    if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        samplesPerFrame = (sampleRate / framesPerSecond).coerceAtLeast(256)
                        currentFrameBuffer = ShortArray(samplesPerFrame)
                        currentFrameCursor = 0
                    }
                    if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                } else if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.order(ByteOrder.nativeOrder())
                        val shortBuf = outBuf.asShortBuffer()
                        val numShorts = shortBuf.remaining()
                        val framesInBuffer = numShorts / channelCount

                        for (f in 0 until framesInBuffer) {
                            var monoSum = 0
                            for (c in 0 until channelCount) {
                                monoSum += shortBuf.get(f * channelCount + c).toInt()
                            }
                            val monoSample = (monoSum / channelCount).toShort()
                            currentFrameBuffer[currentFrameCursor++] = monoSample
                            totalMonoSamplesDecoded++

                            if (currentFrameCursor >= samplesPerFrame) {
                                val windowLen = minOf(512, samplesPerFrame)
                                spectrumFrames.add(
                                    compute24BandSpectrumFromPcmWindow(
                                        pcm = currentFrameBuffer,
                                        offset = 0,
                                        length = windowLen,
                                        sampleRate = sampleRate
                                    )
                                )
                                currentFrameCursor = 0
                                if (spectrumFrames.size >= maxFrames) break
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                }
            }

            if (currentFrameCursor > 32 && spectrumFrames.size < maxFrames) {
                spectrumFrames.add(
                    compute24BandSpectrumFromPcmWindow(
                        pcm = currentFrameBuffer,
                        offset = 0,
                        length = minOf(512, currentFrameCursor),
                        sampleRate = sampleRate
                    )
                )
            }

            codec.stop()
            codec.release()
            decoder = null
            extractor.release()
            extractor = null

            val actualDurationSec = totalMonoSamplesDecoded.toFloat() / sampleRate.toFloat()
            Result.success(
                AudioSpectrumTimeline(
                    framesPerSecond = framesPerSecond,
                    durationSec = actualDurationSec,
                    frames = spectrumFrames,
                    isFromDecodedPcm = true
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode & analyze audio URI", e)
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }
}
