package com.example.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.model.AudioSource
import com.example.model.AudioSpectrumTimeline
import com.example.model.CameraMovementPreset
import com.example.model.EdlAudioSegment
import com.example.model.LyricLine
import com.example.model.ProjectEdl
import com.example.model.StoryboardCue
import com.example.model.VideoAspectRatio
import com.example.model.VideoModelType
import com.example.model.VideoResolution
import com.example.model.VisualFilterStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

private const val TAG = "HdVideoExporter"

data class ExportConfig(
    val sourceBitmap: Bitmap,
    val audioSource: AudioSource,
    val modelType: VideoModelType,
    val resolution: VideoResolution,
    val aspectRatio: VideoAspectRatio,
    val cameraPreset: CameraMovementPreset,
    val filterStyle: VisualFilterStyle,
    val songTitle: String,
    val promptText: String,
    val lyricsEnabled: Boolean,
    val lyrics: List<LyricLine>,
    val storyboardCues: List<StoryboardCue> = emptyList(),
    val spectrumTimeline: AudioSpectrumTimeline? = null,
    val clipStartSec: Float = 0f,
    val clipEndSec: Float = 12f,
    val fps: Int = 24,
    val projectEdl: ProjectEdl? = null
) {
    val exportDurationSec: Float
        get() = projectEdl?.totalEditedDurationSec ?: (clipEndSec - clipStartSec).coerceAtLeast(1f)
}

data class Mp4TrackVerification(
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val videoDurationUs: Long,
    val audioDurationUs: Long,
    val videoMime: String,
    val audioMime: String
)

object HdVideoExporter {

    /**
     * Renders and encodes a real HD .mp4 file (H.264 video + AAC audio track) to disk,
     * consuming the approved [ProjectEdl] and preserving the user's original imported audio
     * via streaming MediaExtractor -> MediaCodec decode -> AAC encode -> MediaMuxer without
     * ever substituting synthetic audio for imported files.
     * Explicitly verifies that the resulting MP4 contains both valid video and audio tracks.
     */
    suspend fun exportToMp4(
        context: Context,
        config: ExportConfig,
        onProgress: (Float, String) -> Unit
    ): Result<File> = withContext(Dispatchers.Default) {
        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val sanitizedTitle = config.songTitle
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(24)
            .ifBlank { "MusicVideo" }
        val tempVideoOnlyFile = File(
            exportsDir,
            "temp_video_${System.currentTimeMillis()}.mp4"
        )
        val outputFile = File(
            exportsDir,
            "${sanitizedTitle}_${config.resolution.shortWidth}p_${System.currentTimeMillis()}.mp4"
        )

        var videoEncoder: MediaCodec? = null
        var inputSurface: android.view.Surface? = null
        var videoMuxer: MediaMuxer? = null
        var videoMuxerStarted = false

        try {
            val (width, height) = config.aspectRatio.calculateDimensions(config.resolution)
            val durationSec = config.exportDurationSec
            val totalFrames = (durationSec * config.fps).toInt().coerceAtLeast(config.fps)
            val bitrate = config.resolution.bitrateMbps * 1_000_000

            onProgress(0.02f, "Configuring ${width}x${height} H.264 HD encoder (${durationSec.toInt()}s)…")

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder = codec
            codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()

            val mediaMuxer = MediaMuxer(tempVideoOnlyFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoMuxer = mediaMuxer
            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1

            fun drainVideoEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    try {
                        codec.signalEndOfInputStream()
                    } catch (e: Exception) {
                        Log.w(TAG, "signalEndOfInputStream warning", e)
                    }
                }
                var timeoutRetries = 0
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream || ++timeoutRetries > 25) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        videoTrackIndex = mediaMuxer.addTrack(newFormat)
                        mediaMuxer.start()
                        videoMuxerStarted = true
                    } else if (outIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outIndex)
                        if (encodedData != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && videoMuxerStarted && videoTrackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            // Step 1: Render every video frame onto the encoder Surface consuming the approved ProjectEdl
            for (frameIdx in 0 until totalFrames) {
                coroutineContext.ensureActive()
                val editedFrameTimeSec = if (config.projectEdl != null) {
                    frameIdx.toFloat() / config.fps
                } else {
                    config.clipStartSec + (frameIdx.toFloat() / config.fps)
                }

                val surfaceCanvas = surface.lockCanvas(null)
                try {
                    VideoFrameRenderer.renderFrame(
                        canvas = surfaceCanvas,
                        width = width,
                        height = height,
                        sourceBitmap = config.sourceBitmap,
                        timeSec = editedFrameTimeSec,
                        durationSec = durationSec.coerceAtLeast(1f),
                        modelType = config.modelType,
                        cameraPreset = config.cameraPreset,
                        filterStyle = config.filterStyle,
                        songTitle = config.songTitle,
                        promptText = config.promptText,
                        lyricsEnabled = config.lyricsEnabled,
                        lyrics = config.lyrics,
                        storyboardCues = config.storyboardCues,
                        spectrumTimeline = config.spectrumTimeline,
                        projectEdl = config.projectEdl
                    )
                } finally {
                    surface.unlockCanvasAndPost(surfaceCanvas)
                }

                drainVideoEncoder(endOfStream = false)

                val progress = 0.05f + (frameIdx.toFloat() / totalFrames) * 0.60f
                if (frameIdx % 6 == 0 || frameIdx == totalFrames - 1) {
                    onProgress(
                        progress,
                        "Rendering HD frame ${frameIdx + 1}/$totalFrames (${width}x${height})…"
                    )
                }
            }

            onProgress(0.66f, "Finalizing H.264 video stream…")
            drainVideoEncoder(endOfStream = true)

            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            videoEncoder = null

            surface.release()
            inputSurface = null

            if (videoMuxerStarted) {
                mediaMuxer.stop()
            }
            mediaMuxer.release()
            videoMuxer = null

            if (!tempVideoOnlyFile.exists() || tempVideoOnlyFile.length() < 512L) {
                return@withContext Result.failure(
                    IllegalStateException("H.264 video encoding failed: temporary video file is empty.")
                )
            }

            // Step 2: Decode original audio (or generate demo synth ONLY for BuiltInSynthTrack)
            // and mux AAC audio + H.264 video into outputFile.
            val muxAudioResult = transcodeAndMuxAudioWithVideo(
                context = context,
                config = config,
                videoOnlyMp4File = tempVideoOnlyFile,
                finalOutputMp4File = outputFile,
                onProgress = onProgress
            )
            tempVideoOnlyFile.delete()

            if (muxAudioResult.isFailure) {
                outputFile.delete()
                return@withContext Result.failure(
                    muxAudioResult.exceptionOrNull()
                        ?: IllegalStateException("Failed to decode or mux audio track.")
                )
            }

            // Step 3: Verify output MP4 contains BOTH video and audio tracks
            val verification = verifyMp4Tracks(outputFile)
            if (!verification.hasVideoTrack || !verification.hasAudioTrack) {
                outputFile.delete()
                return@withContext Result.failure(
                    IllegalStateException(
                        "Export verification failed: MP4 missing required tracks (video=${verification.hasVideoTrack}, audio=${verification.hasAudioTrack})."
                    )
                )
            }

            onProgress(1.0f, "HD MP4 Video & Original Audio Export Complete!")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "HD Video Export failed", e)
            try {
                videoEncoder?.release()
            } catch (_: Exception) {
            }
            try {
                inputSurface?.release()
            } catch (_: Exception) {
            }
            try {
                if (videoMuxerStarted) videoMuxer?.stop()
                videoMuxer?.release()
            } catch (_: Exception) {
            }
            tempVideoOnlyFile.delete()
            outputFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Inspects an MP4 file using [MediaExtractor] to verify the presence and duration of
     * both H.264 video and AAC audio tracks.
     */
    fun verifyMp4Tracks(mp4File: File): Mp4TrackVerification {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor().apply {
                setDataSource(mp4File.absolutePath)
            }
            var hasVideo = false
            var hasAudio = false
            var videoDurUs = 0L
            var audioDurUs = 0L
            var videoMime = ""
            var audioMime = ""

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    fmt.getLong(MediaFormat.KEY_DURATION)
                } else 0L

                if (mime.startsWith("video/")) {
                    hasVideo = true
                    videoDurUs = dur
                    videoMime = mime
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    audioDurUs = dur
                    audioMime = mime
                }
            }
            Mp4TrackVerification(
                hasVideoTrack = hasVideo,
                hasAudioTrack = hasAudio,
                videoDurationUs = videoDurUs,
                audioDurationUs = audioDurUs,
                videoMime = videoMime,
                audioMime = audioMime
            )
        } catch (e: Exception) {
            Log.e(TAG, "verifyMp4Tracks failed", e)
            Mp4TrackVerification(false, false, 0L, 0L, "", "")
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Routes audio transcoding and muxing strictly by local [AudioSource]:
     * - [AudioSource.LocalMp3Uri]: Decodes the original imported audio using MediaExtractor + MediaCodec,
     *   encodes the decoded PCM into AAC, and muxes with H.264 video. Never substitutes synthetic audio.
     * - [AudioSource.BuiltInSynthTrack]: Uses streaming chunked PCM synthesis for built-in demonstration tracks.
     */
    private suspend fun transcodeAndMuxAudioWithVideo(
        context: Context,
        config: ExportConfig,
        videoOnlyMp4File: File,
        finalOutputMp4File: File,
        onProgress: (Float, String) -> Unit
    ): Result<Unit> {
        val activeSegs = config.projectEdl?.activeAudioSegments.orEmpty()
        val effectiveStartSec = activeSegs.firstOrNull()?.sourceStartSec ?: config.clipStartSec
        val effectiveEndSec = activeSegs.lastOrNull()?.sourceEndSec ?: config.clipEndSec

        return when (val src = config.audioSource) {
            is AudioSource.LocalMp3Uri -> {
                onProgress(0.70f, "Decoding original imported audio (${src.displayName}) to AAC…")
                decodeImportedAudioAndMux(
                    context = context,
                    audioUri = src.uri,
                    clipStartUs = (effectiveStartSec * 1_000_000L).toLong(),
                    clipEndUs = (effectiveEndSec * 1_000_000L).toLong(),
                    activeAudioSegments = activeSegs,
                    videoOnlyMp4File = videoOnlyMp4File,
                    finalOutputMp4File = finalOutputMp4File,
                    onProgress = onProgress
                )
            }
            is AudioSource.BuiltInSynthTrack -> {
                onProgress(0.70f, "Encoding demonstration studio track to AAC…")
                encodeBuiltInDemoSynthAndMux(
                    presetIndex = src.synthPresetIndex,
                    clipStartSec = effectiveStartSec,
                    durationSec = config.exportDurationSec,
                    activeAudioSegments = activeSegs,
                    videoOnlyMp4File = videoOnlyMp4File,
                    finalOutputMp4File = finalOutputMp4File,
                    onProgress = onProgress
                )
            }
        }
    }

    /**
     * Decodes an imported local audio file (MP3, WAV, M4A, FLAC, OGG) using [MediaExtractor] and [MediaCodec],
     * filters decoded PCM through [activeAudioSegments] from the nondestructive [ProjectEdl],
     * encodes the resulting PCM stream into AAC (`audio/mp4a-latm`) with matching sample rate and channel
     * count, and muxes it with the H.264 video track into [finalOutputMp4File].
     * Never falls back to synthetic audio; returns [Result.failure] if decoding or muxing fails.
     */
    suspend fun decodeImportedAudioAndMux(
        context: Context,
        audioUri: Uri?,
        clipStartUs: Long,
        clipEndUs: Long,
        activeAudioSegments: List<EdlAudioSegment> = emptyList(),
        videoOnlyMp4File: File,
        finalOutputMp4File: File,
        onProgress: (Float, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Default) {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val targetDurationUs = if (activeAudioSegments.isNotEmpty()) {
                (activeAudioSegments.sumOf { it.sourceDurationSec.toDouble() } * 1_000_000.0).toLong().coerceAtLeast(1_000_000L)
            } else {
                (clipEndUs - clipStartUs).coerceAtLeast(1_000_000L)
            }

            // 1. Open video extractor
            val vExt = MediaExtractor().apply {
                setDataSource(videoOnlyMp4File.absolutePath)
            }
            videoExtractor = vExt
            var sourceVideoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until vExt.trackCount) {
                val fmt = vExt.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    sourceVideoTrack = i
                    videoFormat = fmt
                    break
                }
            }
            if (sourceVideoTrack < 0 || videoFormat == null) {
                return@withContext Result.failure(
                    IllegalStateException("Encoded H.264 video track not found for muxing.")
                )
            }

            // 2. Open original imported audio extractor
            val aExt = MediaExtractor()
            audioExtractor = aExt
            if (audioUri != null) {
                aExt.setDataSource(context, audioUri, null)
            } else {
                return@withContext Result.failure(
                    IllegalArgumentException("No local audio URI provided for audio decoding.")
                )
            }

            var sourceAudioTrack = -1
            var inputAudioFormat: MediaFormat? = null
            var inputAudioMime = ""
            for (i in 0 until aExt.trackCount) {
                val fmt = aExt.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    sourceAudioTrack = i
                    inputAudioFormat = fmt
                    inputAudioMime = mime
                    break
                }
            }

            if (sourceAudioTrack < 0 || inputAudioFormat == null) {
                return@withContext Result.failure(
                    IllegalArgumentException("Original audio source does not contain a valid decodable audio track.")
                )
            }

            aExt.selectTrack(sourceAudioTrack)
            if (clipStartUs > 0L) {
                aExt.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val sampleRate = if (inputAudioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val channelCount = if (inputAudioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            } else {
                1
            }

            // 3. Configure MediaCodec Decoder for original MP3/Audio
            val decoder = MediaCodec.createDecoderByType(inputAudioMime)
            audioDecoder = decoder
            decoder.configure(inputAudioFormat, null, null, 0)
            decoder.start()

            // 4. Configure MediaCodec AAC Encoder matching decoded sampleRate & channelCount
            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder = encoder
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val mediaMuxer = MediaMuxer(finalOutputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            val muxVideoTrackIndex = mediaMuxer.addTrack(videoFormat)
            var muxAudioTrackIndex = -1

            val decodeInfo = MediaCodec.BufferInfo()
            val encodeInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var totalPcmBytesQueuedToEncoder = 0L
            val bytesPerSecond = (sampleRate * channelCount * 2).toLong().coerceAtLeast(1L)

            // Queue of decoded PCM byte chunks waiting for an AAC encoder input buffer
            val pendingPcmQueue = ArrayDeque<ByteArray>()

            fun isSourceTimeInActiveSegment(sourcePtsUs: Long): Boolean {
                if (activeAudioSegments.isEmpty()) return true
                val sec = sourcePtsUs / 1_000_000f
                return activeAudioSegments.any { seg ->
                    sec >= seg.sourceStartSec - 0.02f && sec <= seg.sourceEndSec + 0.02f
                }
            }

            while (!encoderDone) {
                coroutineContext.ensureActive()

                // A. Feed compressed MP3/audio packets from MediaExtractor into Decoder
                if (!extractorDone) {
                    val decInIdx = decoder.dequeueInputBuffer(5_000L)
                    if (decInIdx >= 0) {
                        val decInBuf = decoder.getInputBuffer(decInIdx)
                        if (decInBuf != null) {
                            decInBuf.clear()
                            val sampleSize = aExt.readSampleData(decInBuf, 0)
                            val sampleTimeUs = aExt.sampleTime
                            if (sampleSize < 0 || (sampleTimeUs > clipEndUs && sampleTimeUs > 0L)) {
                                decoder.queueInputBuffer(
                                    decInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                extractorDone = true
                            } else {
                                val relativePtsUs = (sampleTimeUs - clipStartUs).coerceAtLeast(0L)
                                decoder.queueInputBuffer(decInIdx, 0, sampleSize, relativePtsUs, 0)
                                aExt.advance()
                            }
                        }
                    }
                }

                // B. Drain decoded PCM buffers from Decoder into pendingPcmQueue (omitting cut segments)
                if (!decoderDone && pendingPcmQueue.size < 16) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decodeInfo, 5_000L)
                    if (decOutIdx >= 0) {
                        val decOutBuf = decoder.getOutputBuffer(decOutIdx)
                        val sourcePtsUs = clipStartUs + decodeInfo.presentationTimeUs
                        if (decOutBuf != null &&
                            decodeInfo.size > 0 &&
                            decodeInfo.presentationTimeUs >= 0L &&
                            isSourceTimeInActiveSegment(sourcePtsUs)
                        ) {
                            decOutBuf.position(decodeInfo.offset)
                            decOutBuf.limit(decodeInfo.offset + decodeInfo.size)
                            val pcmChunk = ByteArray(decodeInfo.size)
                            decOutBuf.get(pcmChunk)
                            pendingPcmQueue.addLast(pcmChunk)
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        if ((decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                        }
                    }
                }

                // C. Feed decoded PCM chunks from pendingPcmQueue into AAC Encoder
                while (pendingPcmQueue.isNotEmpty() || decoderDone) {
                    val encInIdx = encoder.dequeueInputBuffer(5_000L)
                    if (encInIdx < 0) break
                    val encInBuf = encoder.getInputBuffer(encInIdx) ?: break
                    encInBuf.clear()

                    if (pendingPcmQueue.isNotEmpty()) {
                        val chunk = pendingPcmQueue.removeFirst()
                        val writeLen = minOf(chunk.size, encInBuf.remaining())
                        encInBuf.put(chunk, 0, writeLen)
                        if (writeLen < chunk.size) {
                            // Re-queue remainder at front
                            pendingPcmQueue.addFirst(chunk.copyOfRange(writeLen, chunk.size))
                        }
                        val ptsUs = (totalPcmBytesQueuedToEncoder * 1_000_000L) / bytesPerSecond
                        totalPcmBytesQueuedToEncoder += writeLen
                        if (ptsUs >= targetDurationUs) {
                            pendingPcmQueue.clear()
                            decoderDone = true
                            encoder.queueInputBuffer(
                                encInIdx, 0, writeLen, targetDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            break
                        } else {
                            encoder.queueInputBuffer(encInIdx, 0, writeLen, ptsUs, 0)
                        }
                    } else if (decoderDone) {
                        val finalPtsUs = (totalPcmBytesQueuedToEncoder * 1_000_000L) / bytesPerSecond
                        encoder.queueInputBuffer(
                            encInIdx, 0, 0, finalPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        break
                    }
                }

                // D. Drain encoded AAC packets from Encoder into MediaMuxer
                val encOutIdx = encoder.dequeueOutputBuffer(encodeInfo, 5_000L)
                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxAudioTrackIndex = mediaMuxer.addTrack(encoder.outputFormat)
                    mediaMuxer.start()
                    muxerStarted = true

                    // Copy all encoded H.264 video frames now that both tracks are registered
                    vExt.selectTrack(sourceVideoTrack)
                    val copyBuf = ByteBuffer.allocate(512 * 1024)
                    val videoCopyInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val size = vExt.readSampleData(copyBuf, 0)
                        if (size < 0) break
                        videoCopyInfo.offset = 0
                        videoCopyInfo.size = size
                        videoCopyInfo.presentationTimeUs = vExt.sampleTime
                        videoCopyInfo.flags = vExt.sampleFlags
                        mediaMuxer.writeSampleData(muxVideoTrackIndex, copyBuf, videoCopyInfo)
                        vExt.advance()
                    }
                } else if (encOutIdx >= 0) {
                    val encodedAudio = encoder.getOutputBuffer(encOutIdx)
                    if (encodedAudio != null && muxerStarted && muxAudioTrackIndex >= 0) {
                        if ((encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encodeInfo.size = 0
                        }
                        if (encodeInfo.size > 0) {
                            encodedAudio.position(encodeInfo.offset)
                            encodedAudio.limit(encodeInfo.offset + encodeInfo.size)
                            mediaMuxer.writeSampleData(muxAudioTrackIndex, encodedAudio, encodeInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                    val currentPtsUs = encodeInfo.presentationTimeUs.coerceAtLeast(0L)
                    val audioRatio = (currentPtsUs.toFloat() / targetDurationUs.toFloat()).coerceIn(0f, 1f)
                    onProgress(
                        0.70f + audioRatio * 0.27f,
                        "Transcoding original audio to AAC (${(audioRatio * 100).toInt()}%)…"
                    )
                    if ((encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                } else if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER && decoderDone && pendingPcmQueue.isEmpty()) {
                    break
                }
            }

            if (totalPcmBytesQueuedToEncoder <= 0L || !muxerStarted) {
                return@withContext Result.failure(
                    IllegalStateException("Original audio decoding produced zero PCM audio samples.")
                )
            }

            decoder.stop()
            decoder.release()
            audioDecoder = null

            encoder.stop()
            encoder.release()
            audioEncoder = null

            aExt.release()
            audioExtractor = null

            vExt.release()
            videoExtractor = null

            mediaMuxer.stop()
            mediaMuxer.release()
            muxer = null

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "decodeImportedAudioAndMux failed", e)
            try {
                audioDecoder?.release()
            } catch (_: Exception) {
            }
            try {
                audioEncoder?.release()
            } catch (_: Exception) {
            }
            try {
                audioExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                videoExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }

    /**
     * Used ONLY for built-in studio demonstration tracks ([AudioSource.BuiltInSynthTrack]).
     * Encodes synthesized PCM audio (honoring [activeAudioSegments] trims/cuts) into AAC
     * and muxes with the H.264 video track.
     */
    private suspend fun encodeBuiltInDemoSynthAndMux(
        presetIndex: Int,
        clipStartSec: Float,
        durationSec: Float,
        activeAudioSegments: List<EdlAudioSegment> = emptyList(),
        videoOnlyMp4File: File,
        finalOutputMp4File: File,
        onProgress: (Float, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Default) {
        var videoExtractor: MediaExtractor? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val maxSourceSec = if (activeAudioSegments.isNotEmpty()) {
                activeAudioSegments.maxOf { it.sourceEndSec }.toInt().coerceAtLeast(1) + 1
            } else {
                (clipStartSec + durationSec).toInt().coerceAtLeast(1)
            }
            val fullSamples = SynthAudioGenerator.generatePcmSamples(
                presetIndex = presetIndex,
                durationSec = maxSourceSec
            )
            val pcmSamples: ShortArray = if (activeAudioSegments.isNotEmpty()) {
                val slices = activeAudioSegments.map { seg ->
                    val sIdx = (seg.sourceStartSec * SynthAudioGenerator.SAMPLE_RATE).toInt().coerceIn(0, fullSamples.size)
                    val eIdx = (seg.sourceEndSec * SynthAudioGenerator.SAMPLE_RATE).toInt().coerceIn(sIdx, fullSamples.size)
                    fullSamples.copyOfRange(sIdx, eIdx)
                }
                val totalShorts = slices.sumOf { it.size }
                val combined = ShortArray(totalShorts)
                var pos = 0
                for (slice in slices) {
                    System.arraycopy(slice, 0, combined, pos, slice.size)
                    pos += slice.size
                }
                combined
            } else {
                val startSampleIdx = (clipStartSec * SynthAudioGenerator.SAMPLE_RATE).toInt()
                    .coerceIn(0, fullSamples.size)
                val countSamples = (durationSec * SynthAudioGenerator.SAMPLE_RATE).toInt()
                    .coerceAtMost(fullSamples.size - startSampleIdx)
                fullSamples.copyOfRange(startSampleIdx, startSampleIdx + countSamples)
            }

            val vExt = MediaExtractor().apply {
                setDataSource(videoOnlyMp4File.absolutePath)
            }
            videoExtractor = vExt
            var sourceVideoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until vExt.trackCount) {
                val fmt = vExt.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    sourceVideoTrack = i
                    videoFormat = fmt
                    break
                }
            }
            if (sourceVideoTrack < 0 || videoFormat == null) {
                return@withContext Result.failure(IllegalStateException("Missing H.264 track."))
            }

            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SynthAudioGenerator.SAMPLE_RATE,
                1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder = encoder
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val mediaMuxer = MediaMuxer(finalOutputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            val muxVideoTrackIndex = mediaMuxer.addTrack(videoFormat)
            var muxAudioTrackIndex = -1

            val audioInfo = MediaCodec.BufferInfo()
            var sampleCursor = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val maxShorts = minOf(2048, pcmSamples.size - sampleCursor)
                            if (maxShorts <= 0) {
                                val ptsUs = (sampleCursor.toLong() * 1_000_000L) / SynthAudioGenerator.SAMPLE_RATE
                                encoder.queueInputBuffer(
                                    inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                for (k in 0 until maxShorts) {
                                    val s = pcmSamples[sampleCursor + k].toInt()
                                    inBuf.put((s and 0xFF).toByte())
                                    inBuf.put(((s shr 8) and 0xFF).toByte())
                                }
                                val ptsUs = (sampleCursor.toLong() * 1_000_000L) / SynthAudioGenerator.SAMPLE_RATE
                                encoder.queueInputBuffer(inIdx, 0, maxShorts * 2, ptsUs, 0)
                                sampleCursor += maxShorts
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(audioInfo, 10_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxAudioTrackIndex = mediaMuxer.addTrack(encoder.outputFormat)
                    mediaMuxer.start()
                    muxerStarted = true

                    vExt.selectTrack(sourceVideoTrack)
                    val copyBuf = ByteBuffer.allocate(512 * 1024)
                    val videoCopyInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val size = vExt.readSampleData(copyBuf, 0)
                        if (size < 0) break
                        videoCopyInfo.offset = 0
                        videoCopyInfo.size = size
                        videoCopyInfo.presentationTimeUs = vExt.sampleTime
                        videoCopyInfo.flags = vExt.sampleFlags
                        mediaMuxer.writeSampleData(muxVideoTrackIndex, copyBuf, videoCopyInfo)
                        vExt.advance()
                    }
                } else if (outIdx >= 0) {
                    val encodedAudio = encoder.getOutputBuffer(outIdx)
                    if (encodedAudio != null && muxerStarted && muxAudioTrackIndex >= 0) {
                        if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            audioInfo.size = 0
                        }
                        if (audioInfo.size > 0) {
                            encodedAudio.position(audioInfo.offset)
                            encodedAudio.limit(audioInfo.offset + audioInfo.size)
                            mediaMuxer.writeSampleData(muxAudioTrackIndex, encodedAudio, audioInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            encoder.stop()
            encoder.release()
            audioEncoder = null

            vExt.release()
            videoExtractor = null

            if (muxerStarted) {
                mediaMuxer.stop()
            }
            mediaMuxer.release()
            muxer = null

            onProgress(0.98f, "Finalizing MP4 container…")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "encodeBuiltInDemoSynthAndMux failed", e)
            try {
                audioEncoder?.release()
            } catch (_: Exception) {
            }
            try {
                videoExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {
            }
            Result.failure(e)
        }
    }

    /**
     * Saves the exported MP4 video into the device's public Movies/MusicVideoStudio collection
     * via MediaStore (zero storage permission required on modern Android).
     */
    suspend fun saveToMoviesGallery(context: Context, mp4File: File, title: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, mp4File.name)
                    put(MediaStore.Video.Media.TITLE, title)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_MOVIES}/MusicVideoStudio"
                        )
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val itemUri = resolver.insert(collection, values)
                    ?: return@withContext Result.failure(IllegalStateException("Could not create MediaStore entry."))

                resolver.openOutputStream(itemUri)?.use { outStream ->
                    FileInputStream(mp4File).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completeValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, completeValues, null, null)
                }

                Result.success(itemUri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save MP4 to MediaStore", e)
                Result.failure(e)
            }
        }
}
