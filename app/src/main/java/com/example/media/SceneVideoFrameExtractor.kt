package com.example.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import com.example.studio.storage.db.StudioDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts and caches decoded video frames from verified per-scene MP4 assets resolved by
 * immutable `assetId` at the rendering boundary (`AssetManifestDao` / `studio_assets/<assetId>.mp4`).
 *
 * Ensures `EdlSceneClip`, `SceneVersionSnapshot`, and `StoryboardCue` remain 100% free of
 * raw filesystem paths while allowing both the real-time Preview Canvas and `HdVideoExporter`
 * to render hybrid photo + MP4 timelines.
 */
object SceneVideoFrameExtractor {

    private const val TAG = "SceneVideoExtractor"

    // Cache quantized frames (at 24 buckets per assetId) to keep preview smooth
    private val frameCache = object : LruCache<String, Bitmap>(36) {}
    private val durationMsCache = ConcurrentHashMap<String, Long>()
    private val resolvedAssetFileCache = ConcurrentHashMap<String, File>()

    @Volatile
    private var registeredBaseFilesDir: File? = null

    @Volatile
    private var registeredDatabase: StudioDatabase? = null

    fun registerAssetStorageRoot(
        baseFilesDir: File,
        database: StudioDatabase? = null
    ) {
        registeredBaseFilesDir = baseFilesDir
        if (database != null) {
            registeredDatabase = database
        }
    }

    fun cacheAssetFile(assetId: String, file: File) {
        if (assetId.isNotBlank() && file.exists()) {
            resolvedAssetFileCache[assetId] = file
        }
    }

    fun clearCachesForTest() {
        resolvedAssetFileCache.clear()
        durationMsCache.clear()
        synchronized(this) {
            frameCache.evictAll()
        }
    }

    /**
     * Resolves an immutable [assetId] to its verified local [File] at the rendering boundary:
     * 1. Custom resolver callback if supplied by caller/test.
     * 2. In-memory verified asset file cache populated during `AssetManager` promotion/resolution.
     * 3. Room `asset_manifest` lookup via [registeredDatabase] if available.
     * 4. Canonical `studio_assets/<assetId>.mp4` under [registeredBaseFilesDir].
     */
    fun resolveAssetFile(
        assetId: String,
        customResolver: ((String) -> File?)? = null
    ): File? {
        if (assetId.isBlank()) return null

        customResolver?.invoke(assetId)?.let { file ->
            if (file.exists() && file.isFile) {
                resolvedAssetFileCache[assetId] = file
                return file
            }
        }

        resolvedAssetFileCache[assetId]?.let { cached ->
            if (cached.exists() && cached.isFile) return cached
        }

        val db = registeredDatabase
        val baseDir = registeredBaseFilesDir
        if (db != null && baseDir != null) {
            try {
                val manifest = runBlocking { db.assetManifestDao().getByAssetId(assetId) }
                if (manifest != null) {
                    val file = File(baseDir, manifest.relativePath)
                    if (file.exists() && file.length() == manifest.byteSize) {
                        resolvedAssetFileCache[assetId] = file
                        return file
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Manifest lookup fallback for assetId=$assetId", e)
            }
        }
        if (baseDir != null) {
            val candidate = File(File(baseDir, "studio_assets"), "$assetId.mp4")
            if (candidate.exists() && candidate.isFile) {
                resolvedAssetFileCache[assetId] = candidate
                return candidate
            }
        }

        return null
    }

    @Synchronized
    fun getFrameForAssetId(
        assetId: String,
        sceneLocalProgress: Float,
        customResolver: ((String) -> File?)? = null
    ): Bitmap? {
        val file = resolveAssetFile(assetId, customResolver) ?: return null
        return getFrameFromVerifiedFile(assetId, file, sceneLocalProgress)
    }

    @Synchronized
    fun getFrameAtSceneProgress(
        videoFilePath: String,
        sceneLocalProgress: Float
    ): Bitmap? {
        val file = File(videoFilePath)
        if (!file.exists() || file.length() < 64L) return null
        return getFrameFromVerifiedFile(file.nameWithoutExtension, file, sceneLocalProgress)
    }

    private fun getFrameFromVerifiedFile(
        cacheNamespace: String,
        file: File,
        sceneLocalProgress: Float
    ): Bitmap? {
        if (!file.exists() || file.length() < 64L) return null

        val clampedProgress = sceneLocalProgress.coerceIn(0f, 1f)
        val bucketIndex = (clampedProgress * 24f).toInt().coerceIn(0, 24)
        val cacheKey = "$cacheNamespace@$bucketIndex"

        frameCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = durationMsCache.getOrPut(file.absolutePath) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(500L)
                    ?: 5_000L
            }
            val targetTimeUs = ((clampedProgress * durationMs) * 1000L).toLong()
                .coerceIn(0L, (durationMs - 50L).coerceAtLeast(0L) * 1000L)

            val rawFrame = retriever.getFrameAtTime(
                targetTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(targetTimeUs)

            if (rawFrame != null) {
                val maxDim = 1280
                val w = rawFrame.width.coerceAtLeast(1)
                val h = rawFrame.height.coerceAtLeast(1)
                val stored = if (w > maxDim || h > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
                    Bitmap.createScaledBitmap(
                        rawFrame,
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    rawFrame
                }
                frameCache.put(cacheKey, stored)
                stored
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract frame for asset $cacheNamespace", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
