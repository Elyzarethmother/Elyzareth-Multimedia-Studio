package com.example.studio.ingestion

import android.content.Context
import android.net.Uri
import com.example.media.SceneVideoFrameExtractor
import com.example.studio.storage.db.AssetManifestEntity
import com.example.studio.storage.db.GenerationJobEntity
import com.example.studio.storage.db.GenerationJobKind
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.PromotionJournalEntity
import com.example.studio.storage.db.PromotionPhase
import com.example.studio.storage.db.StudioDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Owns the provider-independent media ingestion lifecycle:
 * - Staging file allocation (`filesDir/.staging_tmp/job_<jobId>.part`)
 * - Streaming InputStream / ContentResolver copy
 * - Streaming SHA-256 verification (`ChecksumVerifier`)
 * - Container, track, codec, dimension, duration & VFR validation (`MediaAssetValidator` + `MediaExtractorProbe`)
 * - Durable Promotion Journal (`PREPARED` -> `PROMOTED` -> `COMMITTED`)
 * - POSIX atomic file promotion (`AtomicFilePromoter`) into `filesDir/studio_assets/<assetId>.mp4`
 * - Transactional Room manifest registration & job completion (`StudioDatabase.commitPromotedAsset`)
 * - Immutable `AssetDescriptor` lookup and Content URI / file resolution by `assetId`
 */
class AssetManager(
    private val baseFilesDir: File,
    private val database: StudioDatabase,
    private val validatorInspector: VideoContainerInspector = MediaAssetValidator.defaultInspector,
    private val frameDecodabilityVerifier: FrameDecodabilityVerifier = MediaAssetValidator.defaultFrameDecodabilityVerifier
) {

    val stagingDir: File = File(baseFilesDir, ".staging_tmp").apply { mkdirs() }
    val studioAssetsDir: File = File(baseFilesDir, "studio_assets").apply { mkdirs() }
    val studioMetadataDir: File = File(baseFilesDir, "studio_metadata").apply { mkdirs() }

    private val atomicPromoter = AtomicFilePromoter(studioAssetsDir)

    init {
        // Register this AssetManager's baseFilesDir with SceneVideoFrameExtractor so
        // VideoFrameRenderer resolves immutable assetIds at the rendering boundary.
        SceneVideoFrameExtractor.registerAssetStorageRoot(baseFilesDir, database)
    }

    /**
     * Allocates a staging file in `filesDir/.staging_tmp/job_<jobId>.part` on the same
     * filesystem mount as `filesDir/studio_assets/`.
     */
    fun allocateStagingFile(jobId: String): File {
        stagingDir.mkdirs()
        val safeJobId = jobId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(stagingDir, "job_$safeJobId.part")
    }

    /**
     * Computes a deterministic client-side idempotency key from the scene, prompt, provider, and seed.
     */
    fun computeIdempotencyKey(
        sceneId: String,
        providerId: String,
        modelId: String,
        prompt: String,
        seedOrSourceKey: String
    ): String {
        val raw = "$sceneId|$providerId|$modelId|${prompt.trim()}|$seedOrSourceKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Copies [inputStream] into the job's `.part` staging file using a 64 KB buffer without
     * loading the entire media payload into memory.
     */
    suspend fun writeStreamToStagingFile(
        jobId: String,
        inputStream: InputStream
    ): File = withContext(Dispatchers.IO) {
        val stagingFile = allocateStagingFile(jobId)
        inputStream.use { input ->
            FileOutputStream(stagingFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        stagingFile
    }

    /**
     * Executes the complete recoverable validation + durable promotion journal + atomic promotion +
     * `withTransaction` manifest commit for a staged file belonging to [jobId].
     *
     * Required order enforced:
     * 1. Validate staged media and compute SHA-256.
     * 2. In Room, insert `PREPARED` journal for exact `(jobId, assetId)`.
     * 3. Atomically rename staged file to final path on the same filesystem.
     * 4. In Room, update journal to `PROMOTED`.
     * 5. In one `withTransaction` block: insert/verify manifest, complete exact job with `resultAssetId`,
     *    mark journal `COMMITTED`.
     */
    suspend fun validatePromoteAndCommitJobAsset(
        jobId: String,
        stagingFile: File,
        expectedSha256Hex: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<AssetDescriptor> = withContext(Dispatchers.IO) {
        val jobDao = database.generationJobDao()
        val manifestDao = database.assetManifestDao()
        val journalDao = database.promotionJournalDao()

        StorageReconciliationManager.markStagingJobActive(jobId)
        try {
            var job = jobDao.getJob(jobId)
                ?: return@withContext Result.failure(IllegalStateException("Job not found: $jobId"))

            // Idempotency check 1: Job already COMPLETED with valid manifest
            if (job.status == GenerationJobStatus.COMPLETED && !job.resultAssetId.isNullOrBlank()) {
                val existing = resolveAssetDescriptor(job.resultAssetId!!)
                if (existing != null) {
                    if (stagingFile.exists()) stagingFile.delete()
                    return@withContext Result.success(existing)
                }
            }

            // Transition QUEUED -> DOWNLOADING (if LOCAL_IMPORT) and DOWNLOADING -> VALIDATING
            if (job.status == GenerationJobStatus.QUEUED && job.jobKind == GenerationJobKind.LOCAL_IMPORT) {
                jobDao.transitionStatus(
                    jobId = jobId,
                    targetStatus = GenerationJobStatus.DOWNLOADING,
                    nowEpochMs = nowEpochMs
                )
                job = jobDao.getJob(jobId) ?: job
            }
            if (job.status == GenerationJobStatus.DOWNLOADING) {
                jobDao.transitionStatus(
                    jobId = jobId,
                    targetStatus = GenerationJobStatus.VALIDATING,
                    nowEpochMs = nowEpochMs
                )
                job = jobDao.getJob(jobId) ?: job
            }

            if (job.status != GenerationJobStatus.VALIDATING) {
                return@withContext Result.failure(
                    IllegalStateException("Job $jobId is in state ${job.status}, expected DOWNLOADING or VALIDATING.")
                )
            }

            // Check if a promotion journal already exists for this exact jobId (e.g. crash/retry recovery)
            val existingJournal = journalDao.getByJobId(jobId)
            if (existingJournal != null) {
                val finalFile = File(baseFilesDir, existingJournal.finalRelativePath)
                when (existingJournal.phase) {
                    PromotionPhase.COMMITTED -> {
                        val desc = resolveAssetDescriptor(existingJournal.assetId)
                        if (desc != null) {
                            if (stagingFile.exists()) stagingFile.delete()
                            return@withContext Result.success(desc)
                        }
                    }
                    PromotionPhase.PROMOTED -> {
                        if (finalFile.exists()) {
                            val validation = MediaAssetValidator.validate(
                                file = finalFile,
                                assetIdForPtsManifest = existingJournal.assetId,
                                metadataDir = studioMetadataDir,
                                expectedSha256Hex = existingJournal.expectedSha256Hex,
                                inspector = validatorInspector,
                                frameDecodabilityVerifier = frameDecodabilityVerifier
                            )
                            if (validation is MediaValidationResult.Valid) {
                                val versionNum = parseVersionFromAssetId(existingJournal.assetId)
                                    ?: (manifestDao.getMaxVersionForScene(job.sceneId) + 1)
                                val ptsFile = File(studioMetadataDir, "${existingJournal.assetId}.pts")
                                val manifest = AssetManifestEntity(
                                    assetId = existingJournal.assetId,
                                    sceneId = job.sceneId,
                                    versionNumber = versionNum,
                                    relativePath = existingJournal.finalRelativePath,
                                    contentUri = SafeUri.toUriString(finalFile),
                                    sha256Hex = validation.sha256Hex,
                                    byteSize = finalFile.length(),
                                    mimeType = "video/mp4",
                                    width = validation.width,
                                    height = validation.height,
                                    durationMs = validation.durationMs,
                                    nominalFps = validation.nominalFps,
                                    isVfr = validation.isVfr,
                                    ptsManifestPath = if (ptsFile.exists()) ptsFile.absolutePath else validation.ptsManifestUri?.path,
                                    createdAtEpochMs = nowEpochMs
                                )
                                database.commitPromotedAsset(
                                    jobId = jobId,
                                    manifest = manifest,
                                    journalId = existingJournal.journalId,
                                    nowEpochMs = nowEpochMs
                                )
                                SceneVideoFrameExtractor.cacheAssetFile(manifest.assetId, finalFile)
                                return@withContext Result.success(toDescriptor(manifest, finalFile))
                            }
                        }
                    }
                    PromotionPhase.PREPARED -> {
                        // If finalFile already exists (process died between OS rename and markPromoted),
                        // advance journal to PROMOTED and commit without re-promoting.
                        if (!stagingFile.exists() && finalFile.exists()) {
                            val actualSha = ChecksumVerifier.computeSha256(finalFile).lowercase()
                            if (actualSha.equals(existingJournal.expectedSha256Hex, ignoreCase = true)) {
                                journalDao.markPromoted(existingJournal.journalId, nowEpochMs)
                                val validation = MediaAssetValidator.validate(
                                    file = finalFile,
                                    assetIdForPtsManifest = existingJournal.assetId,
                                    metadataDir = studioMetadataDir,
                                    expectedSha256Hex = existingJournal.expectedSha256Hex,
                                    inspector = validatorInspector,
                                    frameDecodabilityVerifier = frameDecodabilityVerifier
                                )
                                if (validation is MediaValidationResult.Valid) {
                                    val versionNum = parseVersionFromAssetId(existingJournal.assetId)
                                        ?: (manifestDao.getMaxVersionForScene(job.sceneId) + 1)
                                    val ptsFile = File(studioMetadataDir, "${existingJournal.assetId}.pts")
                                    val manifest = AssetManifestEntity(
                                        assetId = existingJournal.assetId,
                                        sceneId = job.sceneId,
                                        versionNumber = versionNum,
                                        relativePath = existingJournal.finalRelativePath,
                                        contentUri = SafeUri.toUriString(finalFile),
                                        sha256Hex = validation.sha256Hex,
                                        byteSize = finalFile.length(),
                                        mimeType = "video/mp4",
                                        width = validation.width,
                                        height = validation.height,
                                        durationMs = validation.durationMs,
                                        nominalFps = validation.nominalFps,
                                        isVfr = validation.isVfr,
                                        ptsManifestPath = if (ptsFile.exists()) ptsFile.absolutePath else validation.ptsManifestUri?.path,
                                        createdAtEpochMs = nowEpochMs
                                    )
                                    database.commitPromotedAsset(
                                        jobId = jobId,
                                        manifest = manifest,
                                        journalId = existingJournal.journalId,
                                        nowEpochMs = nowEpochMs
                                    )
                                    SceneVideoFrameExtractor.cacheAssetFile(manifest.assetId, finalFile)
                                    return@withContext Result.success(toDescriptor(manifest, finalFile))
                                }
                            }
                        }
                    }
                }
            }

            // Check if an identical verified asset already exists for this scene by SHA-256
            if (stagingFile.exists() && stagingFile.length() >= MediaAssetValidator.MIN_VALID_MP4_BYTES) {
                val preSha = ChecksumVerifier.computeSha256(stagingFile).lowercase()
                if (!expectedSha256Hex.isNullOrBlank() && expectedSha256Hex.lowercase() != preSha) {
                    stagingFile.delete()
                    val msg = "SHA-256 checksum mismatch: expected ${expectedSha256Hex.lowercase()}, got $preSha"
                    jobDao.transitionStatus(
                        jobId = jobId,
                        targetStatus = GenerationJobStatus.FAILED,
                        errorMessage = msg,
                        nowEpochMs = nowEpochMs
                    )
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                val existingDuplicate = manifestDao.getBySceneAndSha256(job.sceneId, preSha)
                if (existingDuplicate != null && existingJournal == null) {
                    val existingFile = File(baseFilesDir, existingDuplicate.relativePath)
                    if (existingFile.exists()) {
                        stagingFile.delete()
                        jobDao.transitionStatus(
                            jobId = jobId,
                            targetStatus = GenerationJobStatus.COMPLETED,
                            resultAssetId = existingDuplicate.assetId,
                            errorMessage = null,
                            nowEpochMs = nowEpochMs
                        )
                        SceneVideoFrameExtractor.cacheAssetFile(existingDuplicate.assetId, existingFile)
                        return@withContext Result.success(toDescriptor(existingDuplicate, existingFile))
                    }
                }
            }

            val nextVersion = existingJournal?.let { parseVersionFromAssetId(it.assetId) }
                ?: (manifestDao.getMaxVersionForScene(job.sceneId) + 1)
            val cleanSceneId = job.sceneId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val assetId = existingJournal?.assetId
                ?: String.format(Locale.US, "asset_%s_v%02d", cleanSceneId, nextVersion)
            val journalId = existingJournal?.journalId ?: "journal_${jobId}_$assetId"
            val stagingRelPath = ".staging_tmp/${stagingFile.name}"
            val finalRelPath = "studio_assets/$assetId.mp4"

            // Step 1: Full validation (existence, size, SHA-256, MediaExtractorProbe track & PTS scan, separate decoder check)
            val validation = MediaAssetValidator.validate(
                file = stagingFile,
                assetIdForPtsManifest = assetId,
                metadataDir = studioMetadataDir,
                expectedSha256Hex = expectedSha256Hex,
                inspector = validatorInspector,
                frameDecodabilityVerifier = frameDecodabilityVerifier
            )

            when (validation) {
                is MediaValidationResult.Invalid -> {
                    stagingFile.delete()
                    if (existingJournal != null) {
                        journalDao.deleteById(existingJournal.journalId)
                    }
                    jobDao.transitionStatus(
                        jobId = jobId,
                        targetStatus = GenerationJobStatus.FAILED,
                        errorMessage = validation.reason,
                        nowEpochMs = nowEpochMs
                    )
                    return@withContext Result.failure(IllegalArgumentException(validation.reason))
                }
                is MediaValidationResult.Valid -> {
                    // Step 2: Write PREPARED promotion journal to Room BEFORE atomic promotion
                    if (existingJournal == null) {
                        val preparedJournal = PromotionJournalEntity(
                            journalId = journalId,
                            jobId = jobId,
                            assetId = assetId,
                            stagingRelativePath = stagingRelPath,
                            finalRelativePath = finalRelPath,
                            expectedSha256Hex = validation.sha256Hex,
                            phase = PromotionPhase.PREPARED,
                            createdAtEpochMs = nowEpochMs,
                            updatedAtEpochMs = nowEpochMs
                        )
                        val inserted = journalDao.insertJournalIfAbsent(preparedJournal)
                        if (inserted == -1L) {
                            throw IllegalStateException("Promotion journal collision for job $jobId or asset $assetId")
                        }
                    }

                    // Step 3: Atomic Promotion into studio_assets/<assetId>.mp4
                    val promotedFile = atomicPromoter.promote(
                        stagingFile = stagingFile,
                        assetId = assetId,
                        expectedSha256Hex = validation.sha256Hex
                    )

                    // Step 4: Mark promotion journal PROMOTED in Room
                    val markedPromoted = journalDao.markPromoted(
                        journalId = journalId,
                        updatedAtEpochMs = nowEpochMs
                    )
                    if (!markedPromoted) {
                        throw IllegalStateException("Failed to advance promotion journal $journalId to PROMOTED")
                    }

                    // Step 5: In one RoomDatabase.withTransaction block, insert/verify manifest,
                    // complete exact jobId, and mark journal COMMITTED.
                    val ptsFile = File(studioMetadataDir, "$assetId.pts")
                    val manifest = AssetManifestEntity(
                        assetId = assetId,
                        sceneId = job.sceneId,
                        versionNumber = nextVersion,
                        relativePath = finalRelPath,
                        contentUri = SafeUri.toUriString(promotedFile),
                        sha256Hex = validation.sha256Hex,
                        byteSize = promotedFile.length(),
                        mimeType = "video/mp4",
                        width = validation.width,
                        height = validation.height,
                        durationMs = validation.durationMs,
                        nominalFps = validation.nominalFps,
                        isVfr = validation.isVfr,
                        ptsManifestPath = if (ptsFile.exists()) ptsFile.absolutePath else validation.ptsManifestUri?.path,
                        createdAtEpochMs = nowEpochMs
                    )

                    val committed = database.commitPromotedAsset(
                        jobId = jobId,
                        manifest = manifest,
                        journalId = journalId,
                        nowEpochMs = nowEpochMs
                    )
                    if (!committed) {
                        return@withContext Result.failure(
                            IllegalStateException("Failed to commit asset manifest & complete job $jobId transactionally.")
                        )
                    }

                    SceneVideoFrameExtractor.cacheAssetFile(manifest.assetId, promotedFile)
                    Result.success(toDescriptor(manifest, promotedFile))
                }
            }
        } catch (e: Exception) {
            stagingFile.delete()
            jobDao.transitionStatus(
                jobId = jobId,
                targetStatus = GenerationJobStatus.FAILED,
                errorMessage = e.localizedMessage ?: "Ingestion pipeline failure",
                nowEpochMs = nowEpochMs
            )
            Result.failure(e)
        } finally {
            StorageReconciliationManager.markStagingJobInactive(jobId)
        }
    }

    /**
     * Idempotent, journal-aware local import execution for a pre-registered `LOCAL_IMPORT` job in Room.
     * Invoked by `LocalImportWorker` using only `jobId` (with `sceneId` and `sourceUri` loaded from Room).
     *
     * Never transitions through `SUBMITTING` or `AMBIGUOUS_SUBMISSION_FAILED`.
     * Uses explicit local-import transition path: `QUEUED -> DOWNLOADING -> VALIDATING -> COMPLETED`.
     */
    suspend fun ingestLocalMp4ForJob(
        jobId: String,
        sceneId: String,
        sourceUri: Uri,
        context: Context? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<AssetDescriptor> = withContext(Dispatchers.IO) {
        val jobDao = database.generationJobDao()
        val journalDao = database.promotionJournalDao()

        val currentJob = jobDao.getJob(jobId)
            ?: return@withContext Result.failure(IllegalStateException("Job $jobId not found"))

        if (currentJob.jobKind != GenerationJobKind.LOCAL_IMPORT) {
            return@withContext Result.failure(
                IllegalStateException("Job $jobId has kind ${currentJob.jobKind}, expected LOCAL_IMPORT")
            )
        }

        // 1. If already COMPLETED with valid manifest, return existing asset idempotently
        if (currentJob.status == GenerationJobStatus.COMPLETED && !currentJob.resultAssetId.isNullOrBlank()) {
            val existing = resolveAssetDescriptor(currentJob.resultAssetId!!)
            if (existing != null) {
                return@withContext Result.success(existing)
            }
        }

        // 2. Check if a promotion journal already exists at PREPARED / PROMOTED / COMMITTED boundary
        val existingJournal = journalDao.getByJobId(jobId)
        val stagingFile = allocateStagingFile(jobId)
        if (existingJournal != null) {
            val finalFile = File(baseFilesDir, existingJournal.finalRelativePath)
            if (existingJournal.phase == PromotionPhase.PROMOTED ||
                existingJournal.phase == PromotionPhase.COMMITTED ||
                (existingJournal.phase == PromotionPhase.PREPARED && (stagingFile.exists() || finalFile.exists()))
            ) {
                return@withContext validatePromoteAndCommitJobAsset(
                    jobId = jobId,
                    stagingFile = stagingFile,
                    expectedSha256Hex = existingJournal.expectedSha256Hex,
                    nowEpochMs = nowEpochMs
                )
            }
        }

        StorageReconciliationManager.markStagingJobActive(jobId)
        try {
            if (currentJob.status == GenerationJobStatus.QUEUED) {
                val moved = jobDao.transitionStatus(
                    jobId = jobId,
                    targetStatus = GenerationJobStatus.DOWNLOADING,
                    nowEpochMs = nowEpochMs
                )
                if (!moved) {
                    return@withContext Result.failure(
                        IllegalStateException("Could not transition local import job $jobId from QUEUED to DOWNLOADING")
                    )
                }
            }

            // Copy source URI into app-owned staging if not already pre-staged
            if (!stagingFile.exists() || stagingFile.length() < MediaAssetValidator.MIN_VALID_MP4_BYTES) {
                val inputStream = openUriInputStream(context, sourceUri)
                    ?: return@withContext Result.failure<AssetDescriptor>(
                        IllegalArgumentException("Unable to open InputStream for local import URI: $sourceUri")
                    ).also {
                        jobDao.transitionStatus(
                            jobId = jobId,
                            targetStatus = GenerationJobStatus.FAILED,
                            errorMessage = "Source URI unreadable: $sourceUri",
                            nowEpochMs = nowEpochMs
                        )
                    }
                writeStreamToStagingFile(jobId, inputStream)
            }

            validatePromoteAndCommitJobAsset(
                jobId = jobId,
                stagingFile = stagingFile,
                expectedSha256Hex = null,
                nowEpochMs = nowEpochMs
            )
        } finally {
            StorageReconciliationManager.markStagingJobInactive(jobId)
        }
    }

    private fun openUriInputStream(context: Context?, sourceUri: Uri): InputStream? {
        val scheme = try {
            sourceUri.scheme
        } catch (_: Throwable) {
            null
        }
        val path = try {
            sourceUri.path
        } catch (_: Throwable) {
            null
        }
        if (scheme == "file" || (scheme == null && !path.isNullOrBlank())) {
            val file = File(path ?: return null)
            if (file.exists() && file.isFile) {
                return file.inputStream()
            }
        }
        val str = sourceUri.toString()
        if (str.startsWith("file://")) {
            val file = File(str.removePrefix("file://"))
            if (file.exists() && file.isFile) {
                return file.inputStream()
            }
        }
        return context?.contentResolver?.openInputStream(sourceUri)
    }

    /**
     * Ingests a user-selected local MP4 URI (e.g. from Android Photo Picker) via ContentResolver
     * through the exact same `LOCAL_IMPORT` job -> staging -> SHA-256 -> MediaValidator ->
     * PromotionJournal -> Atomic Promotion -> `withTransaction` Room Manifest pipeline.
     */
    suspend fun ingestLocalMp4Uri(
        context: Context,
        sceneId: String,
        sourceUri: Uri,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<AssetDescriptor> = withContext(Dispatchers.IO) {
        val stream = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalArgumentException("Cannot open ContentResolver stream for URI: ${e.localizedMessage}")
            )
        } ?: return@withContext Result.failure(
            IllegalArgumentException("ContentResolver returned null InputStream for selected URI.")
        )

        ingestFromInputStream(
            sceneId = sceneId,
            inputStream = stream,
            sourceIdentifier = sourceUri.toString(),
            nowEpochMs = nowEpochMs
        )
    }

    /**
     * Ingests any [InputStream] for [sceneId] through a tracked `LOCAL_IMPORT` job in Room
     * (`QUEUED -> DOWNLOADING -> VALIDATING -> COMPLETED`) without ever entering `SUBMITTING`
     * or `AMBIGUOUS_SUBMISSION_FAILED`.
     */
    suspend fun ingestFromInputStream(
        sceneId: String,
        inputStream: InputStream,
        sourceIdentifier: String,
        expectedSha256Hex: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Result<AssetDescriptor> = withContext(Dispatchers.IO) {
        val jobId = "local_import_${sceneId}_$nowEpochMs"
        val idempotencyKey = computeIdempotencyKey(
            sceneId = sceneId,
            providerId = "local_import",
            modelId = "content_resolver_mp4",
            prompt = "Local MP4 Import",
            seedOrSourceKey = "$sourceIdentifier|$nowEpochMs"
        )

        val jobDao = database.generationJobDao()
        val initialJob = GenerationJobEntity(
            jobId = jobId,
            idempotencyKey = idempotencyKey,
            sceneId = sceneId,
            providerId = "local_import",
            modelId = "content_resolver_mp4",
            prompt = "Local MP4 Import ($sourceIdentifier)",
            status = GenerationJobStatus.QUEUED,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            jobKind = GenerationJobKind.LOCAL_IMPORT,
            sourceUri = sourceIdentifier,
            sourceUriGrantFlags = 0
        )
        jobDao.insertJobIfAbsent(initialJob)

        StorageReconciliationManager.markStagingJobActive(jobId)
        try {
            jobDao.transitionStatus(
                jobId = jobId,
                targetStatus = GenerationJobStatus.DOWNLOADING,
                nowEpochMs = nowEpochMs
            )

            val stagingFile = writeStreamToStagingFile(jobId, inputStream)
            validatePromoteAndCommitJobAsset(
                jobId = jobId,
                stagingFile = stagingFile,
                expectedSha256Hex = expectedSha256Hex,
                nowEpochMs = nowEpochMs
            )
        } finally {
            StorageReconciliationManager.markStagingJobInactive(jobId)
        }
    }

    /**
     * Resolves an immutable [AssetDescriptor] from Room by its [assetId] and verifies that the
     * underlying promoted file exists in `studio_assets/`.
     */
    suspend fun resolveAssetDescriptor(assetId: String): AssetDescriptor? = withContext(Dispatchers.IO) {
        val manifest = database.assetManifestDao().getByAssetId(assetId) ?: return@withContext null
        val file = File(baseFilesDir, manifest.relativePath)
        if (!file.exists() || !file.isFile) return@withContext null
        SceneVideoFrameExtractor.cacheAssetFile(assetId, file)
        toDescriptor(manifest, file)
    }

    /**
     * Resolves the verified local [File] for [assetId] via the Room manifest (`AssetManifestDao`).
     */
    suspend fun resolveAssetFile(assetId: String): File? = withContext(Dispatchers.IO) {
        val manifest = database.assetManifestDao().getByAssetId(assetId) ?: return@withContext null
        val file = File(baseFilesDir, manifest.relativePath)
        if (file.exists() && file.isFile) {
            SceneVideoFrameExtractor.cacheAssetFile(assetId, file)
            file
        } else {
            null
        }
    }

    private fun parseVersionFromAssetId(assetId: String): Int? {
        val versionSplit = assetId.lastIndexOf("_v")
        if (versionSplit < 0 || versionSplit + 2 >= assetId.length) return null
        return assetId.substring(versionSplit + 2).takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun toDescriptor(manifest: AssetManifestEntity, promotedFile: File): AssetDescriptor {
        val ptsUri = manifest.ptsManifestPath?.let { path ->
            val f = File(path)
            if (f.exists()) SafeUri.fromFile(f) else null
        }
        return AssetDescriptor(
            assetId = manifest.assetId,
            contentUri = SafeUri.fromFile(promotedFile),
            sha256Hex = manifest.sha256Hex,
            byteSize = manifest.byteSize,
            mimeType = manifest.mimeType,
            width = manifest.width,
            height = manifest.height,
            durationMs = manifest.durationMs,
            nominalFps = manifest.nominalFps,
            isVfr = manifest.isVfr,
            ptsManifestUri = ptsUri
        )
    }
}
