package com.example.studio.storage.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.StudioDatabase
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Central orchestrator for scheduling persistent media ingestion and recovery work via [WorkManager].
 *
 * Enforces Section 5 rules:
 * - WorkManager [Data] contains ONLY `"jobId" to jobId`.
 * - Network constraints (`NetworkType.CONNECTED`) are attached ONLY when [requiresNetwork] is true
 *   (local MP4 import jobs set `requiresNetwork = false` and execute offline).
 * - Uses deterministic unique work names (`"studio_ingest_job_$jobId"`) with [ExistingWorkPolicy.KEEP].
 */
object GenerationWorkOrchestrator {

    private const val RECOVERY_UNIQUE_WORK_NAME = "studio_storage_recovery_work"
    const val MAX_ALLOWED_WORK_DATA_KEYS = 1
    val JOB_DEDUPLICATION_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

    /**
     * Builds the strict minimal [Data] payload containing only `jobId`.
     */
    fun buildMinimalJobInputData(jobId: String): Data {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        require(jobId.length <= 128) { "jobId exceeds maximum identifier length (128 chars)" }
        return workDataOf(AssetDownloadWorker.KEY_JOB_ID to jobId)
    }

    /**
     * Builds a [OneTimeWorkRequest] for [AssetDownloadWorker].
     * Network constraints are applied ONLY when [requiresNetwork] is `true`.
     */
    fun buildAssetIngestionWorkRequest(
        jobId: String,
        requiresNetwork: Boolean
    ): OneTimeWorkRequest {
        val constraintsBuilder = Constraints.Builder()
        if (requiresNetwork) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED)
        } else {
            constraintsBuilder.setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        }

        return OneTimeWorkRequestBuilder<AssetDownloadWorker>()
            .setInputData(buildMinimalJobInputData(jobId))
            .setConstraints(constraintsBuilder.build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15L,
                TimeUnit.SECONDS
            )
            .addTag("job:$jobId")
            .build()
    }

    /**
     * Builds a [OneTimeWorkRequest] for [LocalImportWorker] (`jobKind = LOCAL_IMPORT`).
     * Local import jobs execute offline (`NetworkType.NOT_REQUIRED`) and carry only `"jobId"` in [Data].
     */
    fun buildLocalImportWorkRequest(jobId: String): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        return OneTimeWorkRequestBuilder<LocalImportWorker>()
            .setInputData(buildMinimalJobInputData(jobId))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15L,
                TimeUnit.SECONDS
            )
            .addTag("job:$jobId")
            .build()
    }

    fun uniqueWorkNameForJob(jobId: String): String = "studio_ingest_job_$jobId"

    fun enqueueLocalImportWork(
        context: Context,
        jobId: String
    ) {
        val request = buildLocalImportWorkRequest(jobId)
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkNameForJob(jobId),
            JOB_DEDUPLICATION_POLICY,
            request
        )
    }

    fun enqueueJobWork(
        context: Context,
        jobId: String,
        requiresNetwork: Boolean
    ) {
        val request = buildAssetIngestionWorkRequest(
            jobId = jobId,
            requiresNetwork = requiresNetwork
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkNameForJob(jobId),
            JOB_DEDUPLICATION_POLICY,
            request
        )
    }

    /**
     * Cancels active WorkManager work for [jobId] (when [context] is provided), cleans up any
     * partial `.part` staging file, and transitions the job in [database] to [GenerationJobStatus.CANCELLED].
     */
    suspend fun recordJobCancellationInDatabase(
        database: StudioDatabase,
        jobId: String,
        stagingFile: File? = null,
        context: Context? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (context != null) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkNameForJob(jobId))
            } catch (_: Exception) {
            }
        }
        if (stagingFile != null && stagingFile.exists()) {
            stagingFile.delete()
        }
        return database.generationJobDao().transitionStatus(
            jobId = jobId,
            targetStatus = GenerationJobStatus.CANCELLED,
            errorMessage = "Job cancelled by user or work cancellation.",
            nowEpochMs = nowEpochMs
        )
    }

    fun enqueueStartupStorageRecovery(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val recoveryRequest = OneTimeWorkRequestBuilder<StorageRecoveryWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            RECOVERY_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            recoveryRequest
        )
    }
}
