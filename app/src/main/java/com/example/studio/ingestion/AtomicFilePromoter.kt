package com.example.studio.ingestion

import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AtomicFilePromoter(
    private val studioAssetsDir: File
) {

    init {
        studioAssetsDir.mkdirs()
    }

    /**
     * Atomically promotes a validated [stagingFile] into `studio_assets/<assetId>.mp4`.
     *
     * Invariants enforced:
     * 1. Verifies [stagingFile] exists and is non-empty.
     * 2. If `studio_assets/<assetId>.mp4` already exists:
     *    - Computes its SHA-256 checksum and compares it to [expectedSha256Hex] (or the staging file's SHA-256).
     *    - If identical, deletes the redundant staging file and returns the existing immutable asset idempotently.
     *    - If different, refuses to overwrite and throws an [IllegalStateException].
     * 3. Executes an OS-level atomic move on the same `filesDir` filesystem mount:
     *    - API 26+: `Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)`
     *    - API 24–25: `Os.rename(src, dst)`
     * 4. Verifies the promoted file exists, matches the expected byte size, and matches the SHA-256 digest.
     */
    suspend fun promote(
        stagingFile: File,
        assetId: String,
        expectedSha256Hex: String? = null
    ): File = withContext(Dispatchers.IO) {
        if (!stagingFile.exists() || !stagingFile.isFile) {
            throw IllegalStateException("Cannot promote missing staging file: ${stagingFile.absolutePath}")
        }
        val expectedBytes = stagingFile.length()
        if (expectedBytes < MediaAssetValidator.MIN_VALID_MP4_BYTES) {
            throw IllegalStateException("Cannot promote unvalidated/undersized staging file ($expectedBytes bytes).")
        }

        val sanitizedId = assetId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        if (sanitizedId.isBlank()) {
            throw IllegalArgumentException("assetId must not be blank.")
        }

        val stagingSha256 = expectedSha256Hex?.trim()?.lowercase()
            ?: ChecksumVerifier.computeSha256(stagingFile).lowercase()

        studioAssetsDir.mkdirs()
        val targetFile = File(studioAssetsDir, "$sanitizedId.mp4")

        if (targetFile.exists()) {
            val existingSha256 = ChecksumVerifier.computeSha256(targetFile).lowercase()
            if (existingSha256 == stagingSha256 && targetFile.length() == expectedBytes) {
                // Idempotent reuse of identical verified asset; clean up redundant staging file
                stagingFile.delete()
                return@withContext targetFile
            } else {
                throw IllegalStateException(
                    "Immutable asset protection violation: target asset '${targetFile.name}' already exists " +
                        "with SHA-256 $existingSha256 (attempted overwrite with $stagingSha256)."
                )
            }
        }

        // Perform POSIX atomic rename/move on the same filesystem mount
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || Build.VERSION.SDK_INT == 0) {
            Files.move(
                stagingFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } else {
            Os.rename(stagingFile.absolutePath, targetFile.absolutePath)
        }

        if (!targetFile.exists() || targetFile.length() != expectedBytes) {
            throw IllegalStateException(
                "Atomic promotion verification failed for '${targetFile.name}': expected $expectedBytes bytes, found ${targetFile.length()}."
            )
        }

        val promotedSha256 = ChecksumVerifier.computeSha256(targetFile).lowercase()
        if (promotedSha256 != stagingSha256) {
            targetFile.delete()
            throw IllegalStateException(
                "Post-promotion SHA-256 verification failed for '${targetFile.name}': expected $stagingSha256, got $promotedSha256."
            )
        }

        // Best-effort read-only flag as an additional safeguard (not relied upon as sole boundary)
        targetFile.setReadOnly()
        targetFile
    }
}
