package com.example.studio.ingestion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface ChecksumVerificationResult {
    data class Verified(val sha256Hex: String) : ChecksumVerificationResult
    data class Mismatch(val expectedHex: String, val actualHex: String) : ChecksumVerificationResult
    data class Error(val reason: String) : ChecksumVerificationResult
}

object ChecksumVerifier {

    private const val STREAM_BUFFER_BYTES = 64 * 1024

    suspend fun computeSha256(file: File): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")

            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)

                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break

                    digest.update(buffer, 0, count)
                }
            }

            digest.digest().joinToString("") {
                "%02x".format(it)
            }
        }

    /**
     * Always calculates the local streaming SHA-256 checksum.
     * If [expectedSha256Hex] is provided, verifies case-insensitively that the calculated
     * digest matches it.
     */
    suspend fun verifyFileChecksum(
        file: File,
        expectedSha256Hex: String? = null
    ): ChecksumVerificationResult {
        if (!file.exists() || !file.isFile) {
            return ChecksumVerificationResult.Error("File does not exist: ${file.name}")
        }
        return try {
            val actualHex = computeSha256(file).lowercase()
            val normalizedExpected = expectedSha256Hex?.trim()?.lowercase()
            if (!normalizedExpected.isNullOrEmpty() && normalizedExpected != actualHex) {
                ChecksumVerificationResult.Mismatch(
                    expectedHex = normalizedExpected,
                    actualHex = actualHex
                )
            } else {
                ChecksumVerificationResult.Verified(actualHex)
            }
        } catch (e: Exception) {
            ChecksumVerificationResult.Error(e.localizedMessage ?: "SHA-256 calculation failed")
        }
    }
}
