package com.example.studio.storage.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.studio.ingestion.AssetManager
import com.example.studio.ingestion.StorageReconciliationManager
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.StudioDatabase

/**
 * WorkManager [CoroutineWorker] that processes a generation/ingestion job using ONLY
 * `jobId` in [WorkerParameters.getInputData], reading all authoritative state from [StudioDatabase].
 *
 * Key safety invariants:
 * - If the job is found in `SUBMITTING` without a persisted `providerOperationId` (indicating
 *   a crash or socket drop during an earlier submission attempt), transitions the job to
 *   `AMBIGUOUS_SUBMISSION_FAILED` and returns [Result.failure] — NEVER repeating an ambiguous
 *   billable POST.
 * - If the job is in `DOWNLOADING` or `VALIDATING` and its staging file (`job_<jobId>.part`) is
 *   ready on disk, runs `AssetManager.validatePromoteAndCommitJobAsset(...)` to validate,
 *   atomically promote, and transactionally commit the `AssetManifestEntity`.
 */
class AssetDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (inputData.keyValueMap.keys != setOf(KEY_JOB_ID)) {
            return Result.failure(workDataOf(KEY_JOB_ID to ""))
        }
        val jobId = inputData.getString(KEY_JOB_ID)?.trim().orEmpty()
        if (jobId.isEmpty()) {
            return Result.failure(workDataOf(KEY_JOB_ID to ""))
        }

        val db = StudioDatabase.getInstance(applicationContext)
        val assetManager = AssetManager(
            baseFilesDir = applicationContext.filesDir,
            database = db
        )

        return when (val outcome = executeJobStep(jobId, db, assetManager)) {
            is JobStepOutcome.Completed -> Result.success(workDataOf(KEY_JOB_ID to jobId))
            is JobStepOutcome.RetryLater -> Result.retry()
            is JobStepOutcome.TerminalFailure -> Result.failure(workDataOf(KEY_JOB_ID to jobId))
        }
    }

    sealed interface JobStepOutcome {
        data class Completed(val assetId: String) : JobStepOutcome
        data class RetryLater(val reason: String) : JobStepOutcome
        data class TerminalFailure(val status: GenerationJobStatus, val reason: String) : JobStepOutcome
    }

    companion object {
        const val KEY_JOB_ID = "jobId"

        /**
         * Deterministic state-machine execution step invoked by [doWork].
         * Ensures job state survives worker recreation and never repeats an interrupted
         * `SUBMITTING` job without a `providerOperationId`.
         */
        suspend fun executeJobStep(
            jobId: String,
            database: StudioDatabase,
            assetManager: AssetManager,
            expectedSha256Hex: String? = null,
            nowEpochMs: Long = System.currentTimeMillis()
        ): JobStepOutcome {
            val jobDao = database.generationJobDao()
            val currentJob = jobDao.getJob(jobId)
                ?: return JobStepOutcome.TerminalFailure(
                    status = GenerationJobStatus.FAILED,
                    reason = "Job $jobId does not exist in StudioDatabase."
                )

            return when (currentJob.status) {
                GenerationJobStatus.COMPLETED -> {
                    JobStepOutcome.Completed(currentJob.resultAssetId.orEmpty())
                }

                GenerationJobStatus.FAILED,
                GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED,
                GenerationJobStatus.CANCELLED -> {
                    JobStepOutcome.TerminalFailure(
                        status = currentJob.status,
                        reason = currentJob.errorMessage ?: "Job already in terminal state ${currentJob.status}"
                    )
                }

                GenerationJobStatus.SUBMITTING -> {
                    // If a worker wakes up / retries and finds a job left in SUBMITTING with no
                    // providerOperationId, the earlier submission attempt was interrupted ambiguously.
                    // Transition immediately to AMBIGUOUS_SUBMISSION_FAILED and DO NOT retry.
                    if (currentJob.providerOperationId.isNullOrBlank()) {
                        jobDao.markInterruptedSubmittingJobAmbiguous(
                            jobId = jobId,
                            nowEpochMs = nowEpochMs
                        )
                        JobStepOutcome.TerminalFailure(
                            status = GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED,
                            reason = "Interrupted SUBMITTING job without providerOperationId marked AMBIGUOUS_SUBMISSION_FAILED."
                        )
                    } else {
                        // A providerOperationId WAS persisted before interruption; advance to SUBMITTED -> PROCESSING
                        jobDao.transitionStatus(
                            jobId = jobId,
                            targetStatus = GenerationJobStatus.SUBMITTED,
                            nowEpochMs = nowEpochMs
                        )
                        JobStepOutcome.RetryLater("Recovered SUBMITTED state for ${currentJob.providerOperationId}")
                    }
                }

                GenerationJobStatus.QUEUED -> {
                    // Check if a local staging file is already present for local ingestion jobs
                    val stagingFile = assetManager.allocateStagingFile(jobId)
                    if ((currentJob.jobKind == com.example.studio.storage.db.GenerationJobKind.LOCAL_IMPORT || currentJob.providerId == "local_import") && stagingFile.exists()) {
                        jobDao.transitionStatus(jobId, GenerationJobStatus.DOWNLOADING, nowEpochMs = nowEpochMs)
                        return validateAndPromoteStagedFile(jobId, stagingFile, assetManager, expectedSha256Hex, nowEpochMs)
                    }
                    // Remote provider adapters are disabled in offline mode; fail safely if no local staging file is present
                    val reason = "Provider '${currentJob.providerId}' adapter is not enabled in local offline mode."
                    jobDao.transitionStatus(jobId, GenerationJobStatus.SUBMITTING, submissionAttemptedAtMs = nowEpochMs, nowEpochMs = nowEpochMs)
                    jobDao.transitionStatus(jobId, GenerationJobStatus.FAILED, errorMessage = reason, nowEpochMs = nowEpochMs)
                    JobStepOutcome.TerminalFailure(GenerationJobStatus.FAILED, reason)
                }

                GenerationJobStatus.SUBMITTED -> {
                    jobDao.transitionStatus(jobId, GenerationJobStatus.PROCESSING, nowEpochMs = nowEpochMs)
                    JobStepOutcome.RetryLater("Transitioned SUBMITTED -> PROCESSING")
                }

                GenerationJobStatus.PROCESSING -> {
                    val stagingFile = assetManager.allocateStagingFile(jobId)
                    if (stagingFile.exists() && stagingFile.length() > 0L) {
                        jobDao.transitionStatus(jobId, GenerationJobStatus.DOWNLOADING, nowEpochMs = nowEpochMs)
                        validateAndPromoteStagedFile(jobId, stagingFile, assetManager, expectedSha256Hex, nowEpochMs)
                    } else {
                        JobStepOutcome.RetryLater("Waiting for staging payload for job $jobId")
                    }
                }

                GenerationJobStatus.DOWNLOADING,
                GenerationJobStatus.VALIDATING -> {
                    val stagingFile = assetManager.allocateStagingFile(jobId)
                    if (!stagingFile.exists()) {
                        val msg = "Staging file missing for job $jobId in state ${currentJob.status}"
                        jobDao.transitionStatus(jobId, GenerationJobStatus.FAILED, errorMessage = msg, nowEpochMs = nowEpochMs)
                        JobStepOutcome.TerminalFailure(GenerationJobStatus.FAILED, msg)
                    } else {
                        validateAndPromoteStagedFile(jobId, stagingFile, assetManager, expectedSha256Hex, nowEpochMs)
                    }
                }
            }
        }

        private suspend fun validateAndPromoteStagedFile(
            jobId: String,
            stagingFile: java.io.File,
            assetManager: AssetManager,
            expectedSha256Hex: String?,
            nowEpochMs: Long
        ): JobStepOutcome {
            val result = assetManager.validatePromoteAndCommitJobAsset(
                jobId = jobId,
                stagingFile = stagingFile,
                expectedSha256Hex = expectedSha256Hex,
                nowEpochMs = nowEpochMs
            )
            return result.fold(
                onSuccess = { descriptor -> JobStepOutcome.Completed(descriptor.assetId) },
                onFailure = { err ->
                    JobStepOutcome.TerminalFailure(
                        status = GenerationJobStatus.FAILED,
                        reason = err.localizedMessage ?: "Validation or atomic promotion failed"
                    )
                }
            )
        }
    }
}
