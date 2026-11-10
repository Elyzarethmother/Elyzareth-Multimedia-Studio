package com.example.studio.ingestion

import android.net.Uri
import java.io.File

/**
 * Provider-independent immutable descriptor for a verified and atomically promoted media asset.
 * Can only be constructed after SHA-256 verification, container/codec validation, VFR inspection,
 * atomic file promotion into `studio_assets/`, and Room manifest commit.
 */
data class AssetDescriptor(
    val assetId: String,
    val contentUri: Uri,
    val sha256Hex: String,
    val byteSize: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val nominalFps: Float,
    val isVfr: Boolean,
    val ptsManifestUri: Uri?
)

/**
 * Safe URI helper that uses Android's `Uri.fromFile(file)` at runtime and provides a non-null
 * `Uri` instance when running inside plain JVM unit tests where `android.jar` returns `null`.
 */
internal object SafeUri {
    fun fromFile(file: File): Uri {
        val androidUri: Uri? = try {
            Uri.fromFile(file)
        } catch (_: Throwable) {
            null
        }
        if (androidUri != null) return androidUri
        return allocateJvmTestUriStub(file.absolutePath)
    }

    fun toUriString(file: File): String {
        val androidUri: Uri? = try {
            Uri.fromFile(file)
        } catch (_: Throwable) {
            null
        }
        val str = try {
            androidUri?.toString()
        } catch (_: Throwable) {
            null
        }
        return if (!str.isNullOrBlank()) str else "file://${file.absolutePath}"
    }

    private fun allocateJvmTestUriStub(absolutePath: String): Uri {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val testUriClass = Class.forName("android.net.JvmTestUri")
            val instance = allocateMethod.invoke(unsafe, testUriClass) as Uri
            val pathField = testUriClass.getDeclaredField("filePath")
            pathField.isAccessible = true
            pathField.set(instance, absolutePath)
            instance
        } catch (e: Throwable) {
            throw IllegalStateException("Unable to instantiate android.net.Uri in JVM environment", e)
        }
    }
}
