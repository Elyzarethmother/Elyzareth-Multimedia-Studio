package com.example.studio.ingestion

import com.example.studio.storage.db.AssetManifestEntity
import com.example.studio.storage.db.GenerationJobKind
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.PromotionJournalEntity
import com.example.studio.storage.db.PromotionPhase
import com.example.studio.storage.db.StudioDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

data class ReconciliationSummary(
    val interruptedSubmittingJobsMarkedAmbiguous: Int,
    val journalEntriesRecovered: Int,
    val journalEntriesFailed: Int,
    val unownedOrCorruptFilesQuarantined: Int,
    val staleStagingFilesDeleted: Int,
    val activeStagingFilesPreserved: Int
) {
    val orphanedAssetsRegistered: Int get() = journalEntriesRecovered
    val corruptOrphansQuarantined: Int get() = unownedOrCorruptFilesQuarantined
}

/**
 * Handles crash recovery and startup reconciliation between the filesystem (`filesDir`)
 * and `StudioDatabase` using the durable `promotion_journal` table:
 *
 * 1. Marks any interrupted `SUBMITTING` provider job (with no `providerOperationId`) as
 *    `AMBIGUOUS_SUBMISSION_FAILED` (never touching `LOCAL_IMPORT` jobs).
 * 2. Iterates `promotion_journal` rows (never guessing ownership by `sceneId`, filename substring,
 *    or directory enumeration order):
 *    - `PREPARED` + staging file exists: resumes validation, atomic promotion, `PROMOTED` update,
 *      and `commitPromotedAsset` transaction for that exact `journal.jobId`.
 *    - `PREPARED` + final file exists and SHA-256 matches `journal.expectedSha256Hex`: advances
 *      journal to `PROMOTED` and commits `commitPromotedAsset` for that exact `journal.jobId`.
 *    - `PREPARED` + neither staging nor final exists (or checksum mismatch): marks that exact
 *      `journal.jobId` as `FAILED` and retains diagnostics.
 *    - `PROMOTED` + final file exists and SHA-256 matches `journal.expectedSha256Hex`: commits
 *      that exact `journal.jobId` and manifest transactionally via `commitPromotedAsset`.
 *    - `PROMOTED` + final file missing or SHA-256 mismatch: does NOT register manifest; marks that
 *      exact `journal.jobId` as `FAILED` and quarantines any corrupt file.
 *    - `COMMITTED`: verifies the manifest reference; never creates another asset.
 * 3. Quarantines any unjournaled/unreferenced file in `studio_assets/` without ever assigning an
 *    orphan file to an arbitrary job.
 * 4. Sweeps `filesDir/.staging_tmp/` safely without deleting active or journaled staging files.
 */
class StorageReconciliationManager(
    private val baseFilesDir: File,
    private val database: StudioDatabase,
    private val validatorInspector: VideoContainerInspector = MediaAssetValidator.defaultInspector,
    private val frameDecodabilityVerifier: FrameDecodabilityVerifier = MediaAssetValidator.defaultFrameDecodabilityVerifier
) {

    val stagingDir: File = File(baseFilesDir, ".staging_tmp")
    val studioAssetsDir: File = File(baseFilesDir, "studio_assets")
    val studioMetadataDir: File = File(baseFilesDir, "studio_metadata")
    val quarantineDir: File = File(baseFilesDir, ".quarantine")

    private val atomicPromoter = AtomicFilePromoter(studioAssetsDir)

    companion object {
        const val DEFAULT_STALE_STAGING_MAX_AGE_MS = 30 * 60 * 1000L // 30 minutes

        private val activeWorkerStagingJobIds: MutableSet<String> =
            Collections.synchronizedSet(HashSet())

        fun markStagingJobActive(jobId: String) {
            activeWorkerStagingJobIds.add(jobId)
        }

        fun markStagingJobInactive(jobId: String) {
            activeWorkerStagingJobIds.remove(jobId)
        }

        fun isStagingJobActiveInWorker(jobId: String): Boolean =
            activeWorkerStagingJobIds.contains(jobId)
    }

    suspend fun reconcileStorageAndDatabase(
        nowEpochMs: Long = System.currentTimeMillis(),
        staleStagingMaxAgeMs: Long = DEFAULT_STALE_STAGING_MAX_AGE_MS,
        protectedAssetIdsFromEdl: Set<String> = emptySet()
    ): ReconciliationSummary = withContext(Dispatchers.IO) {
        stagingDir.mkdirs()
        studioAssetsDir.mkdirs()
        studioMetadataDir.mkdirs()

        val jobDao = database.generationJobDao()
        val manifestDao = database.assetManifestDao()
        val journalDao = database.promotionJournalDao()

        // 1. Transition any interrupted SUBMITTING provider jobs (without providerOperationId)
        //    to AMBIGUOUS_SUBMISSION_FAILED. Local imports never enter SUBMITTING.
        var ambiguousCount = 0
        val submittingJobs = jobDao.getJobsByStatuses(listOf(GenerationJobStatus.SUBMITTING))
        for (job in submittingJobs) {
            if (job.jobKind != GenerationJobKind.LOCAL_IMPORT &&
                !isStagingJobActiveInWorker(job.jobId) &&
                job.providerOperationId.isNullOrBlank()
            ) {
                if (jobDao.markInterruptedSubmittingJobAmbiguous(job.jobId, nowEpochMs = nowEpochMs)) {
                    ambiguousCount++
                }
            }
        }

        // 2. Reconcile exact-job promotion_journal entries
        var journalRecoveredCount = 0
        var journalFailedCount = 0
        var quarantinedCount = 0

        val allJournals = journalDao.getAllJournals()
        for (journal in allJournals) {
            if (isStagingJobActiveInWorker(journal.jobId)) {
                continue
            }

            when (journal.phase) {
                PromotionPhase.PREPARED -> {
                    val outcome = recoverPreparedJournal(journal, nowEpochMs)
                    when (outcome) {
                        JournalRecoveryOutcome.RECOVERED -> journalRecoveredCount++
                        JournalRecoveryOutcome.FAILED -> journalFailedCount++
                        JournalRecoveryOutcome.QUARANTINED_AND_FAILED -> {
                            journalFailedCount++
                            quarantinedCount++
                        }
                        JournalRecoveryOutcome.NO_OP -> Unit
                    }
                }

                PromotionPhase.PROMOTED -> {
                    val outcome = recoverPromotedJournal(journal, nowEpochMs)
                    when (outcome) {
                        JournalRecoveryOutcome.RECOVERED -> journalRecoveredCount++
                        JournalRecoveryOutcome.FAILED -> journalFailedCount++
                        JournalRecoveryOutcome.QUARANTINED_AND_FAILED -> {
                            journalFailedCount++
                            quarantinedCount++
                        }
                        JournalRecoveryOutcome.NO_OP -> Unit
                    }
                }

                PromotionPhase.COMMITTED -> {
                    val manifest = manifestDao.getByAssetId(journal.assetId)
                    val finalFile = File(baseFilesDir, journal.finalRelativePath)
                    if (manifest == null || !finalFile.exists()) {
                        val job = jobDao.getJob(journal.jobId)
                        if (job != null && !job.status.isTerminal) {
                            jobDao.transitionStatus(
                                jobId = journal.jobId,
                                targetStatus = GenerationJobStatus.FAILED,
                                errorMessage = "COMMITTED journal ${journal.journalId} missing manifest or promoted file.",
                                nowEpochMs = nowEpochMs
                            )
                        }
                        journalFailedCount++
                    }
                }
            }
        }

        // 3. Inspect studio_assets/ for any unowned/unjournaled files.
        //    NEVER guess sceneId or jobId from filenames; quarantine any unowned file not in
        //    asset_manifest or promotion_journal (unless protected by protectedAssetIdsFromEdl).
        val refreshedManifestsById = manifestDao.getAllManifests().associateBy { it.assetId }
        val refreshedJournalsByAssetId = journalDao.getAllJournals().associateBy { it.assetId }
        val promotedFiles = studioAssetsDir.listFiles() ?: emptyArray()

        for (file in promotedFiles) {
            if (!file.isFile || !file.name.endsWith(".mp4")) continue
            val assetId = file.nameWithoutExtension
            if (refreshedManifestsById.containsKey(assetId)) continue
            if (refreshedJournalsByAssetId.containsKey(assetId)) continue
            if (assetId in protectedAssetIdsFromEdl) continue

            quarantineDir.mkdirs()
            val quarantinedFile = File(quarantineDir, "${file.name}.quarantined_$nowEpochMs")
            if (file.renameTo(quarantinedFile)) {
                quarantinedCount++
            }
        }

        // 4. Sweep stale staging files in .staging_tmp/
        val activeJobsById = jobDao.getJobsByStatuses(
            listOf(
                GenerationJobStatus.QUEUED,
                GenerationJobStatus.SUBMITTING,
                GenerationJobStatus.SUBMITTED,
                GenerationJobStatus.PROCESSING,
                GenerationJobStatus.DOWNLOADING,
                GenerationJobStatus.VALIDATING
            )
        ).associateBy { it.jobId }

        val preparedStagingRelPaths = journalDao.getByPhases(listOf(PromotionPhase.PREPARED))
            .map { it.stagingRelativePath }
            .toSet()

        var deletedStagingCount = 0
        var preservedStagingCount = 0
        val stagingFiles = stagingDir.listFiles() ?: emptyArray()

        for (stagingFile in stagingFiles) {
            if (!stagingFile.isFile) continue
            val relPath = ".staging_tmp/${stagingFile.name}"
            val jobId = parseJobIdFromStagingFileName(stagingFile.name)
            val ageMs = (nowEpochMs - stagingFile.lastModified()).coerceAtLeast(0L)

            val isOwnedByActiveWorker = jobId != null && isStagingJobActiveInWorker(jobId)
            val isOwnedByActiveJob = jobId != null && activeJobsById.containsKey(jobId)
            val isReferencedByPreparedJournal = relPath in preparedStagingRelPaths
            val isYoungFile = ageMs < staleStagingMaxAgeMs

            if (isOwnedByActiveWorker || isOwnedByActiveJob || isReferencedByPreparedJournal || isYoungFile) {
                preservedStagingCount++
            } else {
                if (stagingFile.delete()) {
                    deletedStagingCount++
                }
            }
        }

        ReconciliationSummary(
            interruptedSubmittingJobsMarkedAmbiguous = ambiguousCount,
            journalEntriesRecovered = journalRecoveredCount,
            journalEntriesFailed = journalFailedCount,
            unownedOrCorruptFilesQuarantined = quarantinedCount,
            staleStagingFilesDeleted = deletedStagingCount,
            activeStagingFilesPreserved = preservedStagingCount
        )
    }

    private enum class JournalRecoveryOutcome {
        RECOVERED,
        FAILED,
        QUARANTINED_AND_FAILED,
        NO_OP
    }

    private suspend fun recoverPreparedJournal(
        journal: PromotionJournalEntity,
        nowEpochMs: Long
    ): JournalRecoveryOutcome {
        val jobDao = database.generationJobDao()
        val journalDao = database.promotionJournalDao()
        val job = jobDao.getJob(journal.jobId) ?: return JournalRecoveryOutcome.FAILED

        if (job.status == GenerationJobStatus.COMPLETED) {
            return JournalRecoveryOutcome.NO_OP
        }

        val stagingFile = File(baseFilesDir, journal.stagingRelativePath)
        val finalFile = File(baseFilesDir, journal.finalRelativePath)

        // Ensure job is in VALIDATING state before commitPromotedAsset
        ensureJobInValidatingState(job.jobId, nowEpochMs)

        if (stagingFile.exists()) {
            val validation = MediaAssetValidator.validate(
                file = stagingFile,
                assetIdForPtsManifest = journal.assetId,
                metadataDir = studioMetadataDir,
                expectedSha256Hex = journal.expectedSha256Hex,
                inspector = validatorInspector,
                frameDecodabilityVerifier = frameDecodabilityVerifier
            )
            when (validation) {
                is MediaValidationResult.Valid -> {
                    return try {
                        val promotedFile = atomicPromoter.promote(
                            stagingFile = stagingFile,
                            assetId = journal.assetId,
                            expectedSha256Hex = journal.expectedSha256Hex
                        )
                        journalDao.markPromoted(journal.journalId, nowEpochMs)
                        commitValidatedJournalAsset(journal, job.sceneId, promotedFile, validation, nowEpochMs)
                        JournalRecoveryOutcome.RECOVERED
                    } catch (e: Exception) {
                        failJobWithDiagnostics(
                            jobId = journal.jobId,
                            reason = "Recovery promotion failed for journal ${journal.journalId}: ${e.message}",
                            nowEpochMs = nowEpochMs
                        )
                        JournalRecoveryOutcome.FAILED
                    }
                }
                is MediaValidationResult.Invalid -> {
                    stagingFile.delete()
                    failJobWithDiagnostics(
                        jobId = journal.jobId,
                        reason = "Staged file failed validation during PREPARED recovery: ${validation.reason}",
                        nowEpochMs = nowEpochMs
                    )
                    return JournalRecoveryOutcome.FAILED
                }
            }
        } else if (finalFile.exists()) {
            // Crash occurred after OS rename but before markPromoted
            val actualSha = ChecksumVerifier.computeSha256(finalFile).lowercase()
            if (!actualSha.equals(journal.expectedSha256Hex, ignoreCase = true)) {
                quarantineFile(finalFile, nowEpochMs)
                failJobWithDiagnostics(
                    jobId = journal.jobId,
                    reason = "PREPARED recovery found final file with SHA-256 mismatch for ${journal.assetId}.",
                    nowEpochMs = nowEpochMs
                )
                return JournalRecoveryOutcome.QUARANTINED_AND_FAILED
            }
            val validation = MediaAssetValidator.validate(
                file = finalFile,
                assetIdForPtsManifest = journal.assetId,
                metadataDir = studioMetadataDir,
                expectedSha256Hex = journal.expectedSha256Hex,
                inspector = validatorInspector,
                frameDecodabilityVerifier = frameDecodabilityVerifier
            )
            return when (validation) {
                is MediaValidationResult.Valid -> {
                    journalDao.markPromoted(journal.journalId, nowEpochMs)
                    commitValidatedJournalAsset(journal, job.sceneId, finalFile, validation, nowEpochMs)
                    JournalRecoveryOutcome.RECOVERED
                }
                is MediaValidationResult.Invalid -> {
                    quarantineFile(finalFile, nowEpochMs)
                    failJobWithDiagnostics(
                        jobId = journal.jobId,
                        reason = "PREPARED recovery final file invalid: ${validation.reason}",
                        nowEpochMs = nowEpochMs
                    )
                    JournalRecoveryOutcome.QUARANTINED_AND_FAILED
                }
            }
        } else {
            // Neither staging nor final file exists for PREPARED journal
            failJobWithDiagnostics(
                jobId = journal.jobId,
                reason = "PREPARED promotion journal ${journal.journalId} has neither staging nor final file on disk.",
                nowEpochMs = nowEpochMs
            )
            return JournalRecoveryOutcome.FAILED
        }
    }

    private suspend fun recoverPromotedJournal(
        journal: PromotionJournalEntity,
        nowEpochMs: Long
    ): JournalRecoveryOutcome {
        val jobDao = database.generationJobDao()
        val job = jobDao.getJob(journal.jobId) ?: return JournalRecoveryOutcome.FAILED

        if (job.status == GenerationJobStatus.COMPLETED) {
            return JournalRecoveryOutcome.NO_OP
        }

        val finalFile = File(baseFilesDir, journal.finalRelativePath)
        if (!finalFile.exists() || !finalFile.isFile) {
            failJobWithDiagnostics(
                jobId = journal.jobId,
                reason = "PROMOTED journal ${journal.journalId} final file is missing: ${journal.finalRelativePath}",
                nowEpochMs = nowEpochMs
            )
            return JournalRecoveryOutcome.FAILED
        }

        val actualSha = ChecksumVerifier.computeSha256(finalFile).lowercase()
        if (!actualSha.equals(journal.expectedSha256Hex, ignoreCase = true)) {
            quarantineFile(finalFile, nowEpochMs)
            failJobWithDiagnostics(
                jobId = journal.jobId,
                reason = "PROMOTED journal ${journal.journalId} SHA-256 mismatch: expected ${journal.expectedSha256Hex}, got $actualSha",
                nowEpochMs = nowEpochMs
            )
            return JournalRecoveryOutcome.QUARANTINED_AND_FAILED
        }

        val validation = MediaAssetValidator.validate(
            file = finalFile,
            assetIdForPtsManifest = journal.assetId,
            metadataDir = studioMetadataDir,
            expectedSha256Hex = journal.expectedSha256Hex,
            inspector = validatorInspector,
            frameDecodabilityVerifier = frameDecodabilityVerifier
        )

        return when (validation) {
            is MediaValidationResult.Valid -> {
                ensureJobInValidatingState(job.jobId, nowEpochMs)
                commitValidatedJournalAsset(journal, job.sceneId, finalFile, validation, nowEpochMs)
                JournalRecoveryOutcome.RECOVERED
            }
            is MediaValidationResult.Invalid -> {
                quarantineFile(finalFile, nowEpochMs)
                failJobWithDiagnostics(
                    jobId = journal.jobId,
                    reason = "PROMOTED journal ${journal.journalId} failed media validation: ${validation.reason}",
                    nowEpochMs = nowEpochMs
                )
                JournalRecoveryOutcome.QUARANTINED_AND_FAILED
            }
        }
    }

    private suspend fun ensureJobInValidatingState(jobId: String, nowEpochMs: Long) {
        val jobDao = database.generationJobDao()
        var current = jobDao.getJob(jobId) ?: return
        if (current.status == GenerationJobStatus.QUEUED && current.jobKind == GenerationJobKind.LOCAL_IMPORT) {
            jobDao.transitionStatus(jobId, GenerationJobStatus.DOWNLOADING, nowEpochMs = nowEpochMs)
            current = jobDao.getJob(jobId) ?: return
        }
        if (current.status == GenerationJobStatus.DOWNLOADING) {
            jobDao.transitionStatus(jobId, GenerationJobStatus.VALIDATING, nowEpochMs = nowEpochMs)
        }
    }

    private suspend fun commitValidatedJournalAsset(
        journal: PromotionJournalEntity,
        sceneId: String,
        finalFile: File,
        validation: MediaValidationResult.Valid,
        nowEpochMs: Long
    ) {
        val manifestDao = database.assetManifestDao()
        val versionNum = parseVersionFromAssetId(journal.assetId)
            ?: (manifestDao.getMaxVersionForScene(sceneId) + 1)
        val ptsFile = File(studioMetadataDir, "${journal.assetId}.pts")
        val manifest = AssetManifestEntity(
            assetId = journal.assetId,
            sceneId = sceneId,
            versionNumber = versionNum,
            relativePath = journal.finalRelativePath,
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
            jobId = journal.jobId,
            manifest = manifest,
            journalId = journal.journalId,
            nowEpochMs = nowEpochMs
        )
    }

    private suspend fun failJobWithDiagnostics(
        jobId: String,
        reason: String,
        nowEpochMs: Long
    ) {
        val jobDao = database.generationJobDao()
        val current = jobDao.getJob(jobId) ?: return
        if (current.status.isTerminal) return
        if (current.status == GenerationJobStatus.QUEUED && current.jobKind == GenerationJobKind.LOCAL_IMPORT) {
            jobDao.transitionStatus(
                jobId = jobId,
                targetStatus = GenerationJobStatus.FAILED,
                errorMessage = reason,
                nowEpochMs = nowEpochMs
            )
            return
        }
        jobDao.transitionStatus(
            jobId = jobId,
            targetStatus = GenerationJobStatus.FAILED,
            errorMessage = reason,
            nowEpochMs = nowEpochMs
        )
    }

    private fun quarantineFile(file: File, nowEpochMs: Long) {
        if (!file.exists()) return
        quarantineDir.mkdirs()
        val target = File(quarantineDir, "${file.name}.quarantined_$nowEpochMs")
        file.renameTo(target)
    }

    private fun parseJobIdFromStagingFileName(fileName: String): String? {
        if (!fileName.startsWith("job_") || !fileName.endsWith(".part")) return null
        return fileName.removePrefix("job_").removeSuffix(".part").takeIf { it.isNotBlank() }
    }

    private fun parseVersionFromAssetId(assetId: String): Int? {
        val versionSplit = assetId.lastIndexOf("_v")
        if (versionSplit < 0 || versionSplit + 2 >= assetId.length) return null
        return assetId.substring(versionSplit + 2).takeWhile { it.isDigit() }.toIntOrNull()
    }
}
