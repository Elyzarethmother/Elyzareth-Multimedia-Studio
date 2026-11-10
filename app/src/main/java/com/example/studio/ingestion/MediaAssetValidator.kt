package com.example.studio.ingestion

import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

sealed interface MediaValidationResult {

    data class Valid(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val nominalFps: Float,
        val isVfr: Boolean,
        val definitiveIsVfr: Boolean?,
        val scanComplete: Boolean,
        val sha256Hex: String,
        val ptsManifestUri: Uri?
    ) : MediaValidationResult

    data class Invalid(
        val reason: String
    ) : MediaValidationResult
}

/**
 * Container & video-track metadata result returned by [VideoContainerInspector].
 * Note: Video-track discovery is strictly distinct from [FrameDecodabilityVerifier].
 */
data class VideoContainerProbeResult(
    val hasVideoTrack: Boolean,
    val videoCodecMime: String?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val ptsTimestampsUs: List<Long>,
    val declaredFps: Float = 24f,
    val isVfr: Boolean? = null,
    val scanComplete: Boolean = true,
    val totalSamplesScanned: Long = 0L,
    val error: String? = null
)

fun interface VideoContainerInspector {
    fun inspect(file: File): VideoContainerProbeResult
}

/**
 * Separate verification contract proving that a video frame can actually be decoded from [file].
 * Track discovery via `MediaExtractor` is NOT proof that a frame can be decoded;
 * `FrameDecodabilityVerifier` verifies frame decoding independently using `MediaMetadataRetriever`
 * (or a dedicated decoder path) and releases native resources in `finally`.
 */
fun interface FrameDecodabilityVerifier {
    fun canDecodeFrame(file: File): Boolean
}

object MediaAssetValidator {

    const val MIN_VALID_MP4_BYTES = 512L

    val SUPPORTED_VIDEO_CODECS = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,       // "video/avc" (H.264)
        MediaFormat.MIMETYPE_VIDEO_HEVC,      // "video/hevc" (H.265)
        MediaFormat.MIMETYPE_VIDEO_MPEG4,     // "video/mp4v-es"
        MediaFormat.MIMETYPE_VIDEO_VP9,       // "video/x-vnd.on2.vp9"
        "video/av01"                          // AV1
    )

    /**
     * Default video-track inspector powered by [MediaExtractorProbe].
     * Scans only the video track in bounded memory without allocating a full-file byte array.
     */
    val defaultInspector = VideoContainerInspector { file ->
        val probe = MediaExtractorProbe.inspect(
            file = file,
            maxSamplesToScan = Long.MAX_VALUE,
            maxPtsEntries = VfrDetector.MAX_PTS_MANIFEST_SAMPLES
        )
        val durationMs = (probe.durationUs / 1_000L).coerceAtLeast(
            if (probe.sampledPtsUs.size >= 2) {
                (probe.sampledPtsUs.last() - probe.sampledPtsUs.first()) / 1_000L
            } else {
                0L
            }
        )
        VideoContainerProbeResult(
            hasVideoTrack = probe.hasVideoTrack,
            videoCodecMime = probe.mime,
            width = probe.width,
            height = probe.height,
            durationMs = durationMs,
            ptsTimestampsUs = probe.sampledPtsUs,
            declaredFps = (probe.nominalFrameRate ?: 24).toFloat(),
            isVfr = probe.isVfr,
            scanComplete = probe.scanComplete,
            totalSamplesScanned = probe.totalSamplesScanned,
            error = probe.error
        )
    }

    /**
     * Default frame-decodability verifier using [MediaMetadataRetriever].
     * Distinct from [MediaExtractorProbe] track discovery; always releases the retriever in `finally`.
     */
    val defaultFrameDecodabilityVerifier = FrameDecodabilityVerifier { file ->
        if (!file.isFile || file.length() <= 0L) return@FrameDecodabilityVerifier false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
            frame != null
        } catch (_: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Checks the 8-byte ISO-BMFF `ftyp` header using [RandomAccessFile] (reading only 8 bytes,
     * never loading the whole file into memory).
     */
    fun hasValidFtypHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 12L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val boxSize = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val boxType = String(typeBytes, Charsets.US_ASCII)
                boxType == "ftyp" && boxSize >= 8L && boxSize <= file.length()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates [file] against all Phase 2B / 2C criteria:
     * 1. File existence
     * 2. Minimum byte size + 8-byte ISO-BMFF `ftyp` header check
     * 3. Streaming SHA-256 verification (64 KB buffer)
     * 4. Video track discovery via [MediaExtractorProbe] (`inspector`)
     * 5. Supported video codec identification
     * 6. Valid dimensions (`width > 0`, `height > 0`) and duration (`durationMs > 0`)
     * 7. Separate frame-decodability verification via [FrameDecodabilityVerifier] (`frameDecodabilityVerifier`)
     * 8. Bounded PTS cadence analysis & VFR flagging
     */
    suspend fun validate(
        file: File,
        assetIdForPtsManifest: String,
        metadataDir: File,
        expectedSha256Hex: String? = null,
        inspector: VideoContainerInspector = defaultInspector,
        frameDecodabilityVerifier: FrameDecodabilityVerifier = defaultFrameDecodabilityVerifier
    ): MediaValidationResult = withContext(Dispatchers.IO) {
        // 1. File existence
        if (!file.exists() || !file.isFile) {
            return@withContext MediaValidationResult.Invalid("Staging file does not exist: ${file.name}")
        }

        // 2. Minimum byte size & MP4 ftyp signature check (reads only 8 bytes)
        val byteSize = file.length()
        if (byteSize < MIN_VALID_MP4_BYTES) {
            return@withContext MediaValidationResult.Invalid(
                "File size ($byteSize bytes) is below minimum valid MP4 size ($MIN_VALID_MP4_BYTES bytes)."
            )
        }
        if (!hasValidFtypHeader(file)) {
            return@withContext MediaValidationResult.Invalid(
                "Corrupt or non-MP4 container: missing valid ISO-BMFF 'ftyp' box header."
            )
        }

        // 3. Streaming SHA-256 checksum verification
        val sha256Hex = when (val checksumRes = ChecksumVerifier.verifyFileChecksum(file, expectedSha256Hex)) {
            is ChecksumVerificationResult.Verified -> checksumRes.sha256Hex
            is ChecksumVerificationResult.Mismatch -> {
                return@withContext MediaValidationResult.Invalid(
                    "SHA-256 checksum mismatch: expected ${checksumRes.expectedHex}, got ${checksumRes.actualHex}."
                )
            }
            is ChecksumVerificationResult.Error -> {
                return@withContext MediaValidationResult.Invalid(
                    "SHA-256 verification error: ${checksumRes.reason}"
                )
            }
        }

        // 4. Video track discovery via MediaExtractor
        val probe = try {
            inspector.inspect(file)
        } catch (e: Exception) {
            return@withContext MediaValidationResult.Invalid(
                "Container parsing failed: ${e.localizedMessage ?: "Malformed MP4 stream"}"
            )
        }

        if (!probe.hasVideoTrack) {
            return@withContext MediaValidationResult.Invalid(
                "MP4 container has no video track (${probe.error ?: "audio-only or empty container"})."
            )
        }

        // 5. Supported video codec identification
        val codecMime = probe.videoCodecMime?.lowercase().orEmpty()
        if (codecMime.isBlank() || codecMime !in SUPPORTED_VIDEO_CODECS) {
            return@withContext MediaValidationResult.Invalid(
                "Unsupported video codec '$codecMime'. Supported codecs: ${SUPPORTED_VIDEO_CODECS.joinToString()}."
            )
        }

        // 6. Valid dimensions and duration
        if (probe.width <= 0 || probe.height <= 0) {
            return@withContext MediaValidationResult.Invalid(
                "Invalid video dimensions: ${probe.width}x${probe.height}."
            )
        }
        if (probe.durationMs <= 0L) {
            return@withContext MediaValidationResult.Invalid(
                "Invalid video duration: ${probe.durationMs} ms."
            )
        }

        // 7. Separate Frame-Decodability Verification (distinct from MediaExtractor track discovery)
        val canDecode = try {
            frameDecodabilityVerifier.canDecodeFrame(file)
        } catch (_: Exception) {
            false
        }
        if (!canDecode) {
            return@withContext MediaValidationResult.Invalid(
                "Decoder verification failed: video track metadata was discovered, but no video frame could be decoded."
            )
        }

        // 8. Bounded PTS cadence analysis & VFR flagging
        val baseVfrReport = VfrDetector.analyzePresentationTimestamps(
            rawPtsUs = probe.ptsTimestampsUs,
            fallbackFps = probe.declaredFps,
            scanComplete = probe.scanComplete
        )
        val combinedVfr = baseVfrReport.isVfr || (probe.isVfr == true)
        val definitiveVfr = if (probe.scanComplete) combinedVfr else null
        val finalVfrReport = baseVfrReport.copy(
            isVfr = combinedVfr,
            scanComplete = probe.scanComplete,
            definitiveIsVfr = definitiveVfr
        )
        val ptsFile = VfrDetector.writePtsManifestFile(
            metadataDir = metadataDir,
            assetId = assetIdForPtsManifest,
            report = finalVfrReport
        )

        MediaValidationResult.Valid(
            width = probe.width,
            height = probe.height,
            durationMs = probe.durationMs,
            nominalFps = finalVfrReport.nominalFps,
            isVfr = combinedVfr,
            definitiveIsVfr = definitiveVfr,
            scanComplete = probe.scanComplete,
            sha256Hex = sha256Hex,
            ptsManifestUri = SafeUri.fromFile(ptsFile)
        )
    }
}
