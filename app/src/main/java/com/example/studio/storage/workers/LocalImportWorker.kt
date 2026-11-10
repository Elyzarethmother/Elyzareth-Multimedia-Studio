package com.example.studio.storage.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.studio.ingestion.AssetManager
import com.example.studio.ingestion.FrameDecodabilityVerifier
import com.example.studio.ingestion.MediaAssetValidator
import com.example.studio.ingestion.VideoContainerInspector
import com.example.studio.storage.db.GenerationJobKind
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.StudioDatabase
import java.io.File

/**
 * WorkManager [CoroutineWorker] dedicated to durable local MP4 asset ingestion (`jobKind = LOCAL_IMPORT`).
 *
 * Enforces Phase 2C rules:
 * - WorkManager [WorkerParameters.getInputData] contains ONLY `"jobId"`.
 * - Loads `sourceUri`, `sceneId`, and job state from Room (`generation_jobs`).
 * - Idempotent on duplicate execution when `status == COMPLETED` and the promoted file + manifest exist.
 * - Never enters `SUBMITTING` or `AMBIGUOUS_SUBMISSION_FAILED`.
 */
class LocalImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Strict invariant: WorkManager inputData must contain ONLY "jobId"
        if (inputData.keyValueMap.keys != setOf(KEY_JOB_ID)) {
            return Result.failure(workDataOf("error" to "WorkManager inputData must contain only jobId"))
        }
        val jobId = inputData.getString(KEY_JOB_ID)?.trim().orEmpty()
        if (jobId.isEmpty()) {
            return Result.failure(workDataOf("error" to "Missing jobId"))
        }

        val db = testDatabaseOverride ?: StudioDatabase.getInstance(applicationContext)
        val baseDir = testBaseFilesDirOverride ?: applicationContext.filesDir
        val inspector = testInspectorOverride ?: MediaAssetValidator.defaultInspector
        val decodabilityVerifier = testDecodabilityVerifierOverride ?: MediaAssetValidator.defaultFrameDecodabilityVerifier

        return executeForJob(
            context = applicationContext,
            jobId = jobId,
            database = db,
            baseFilesDir = baseDir,
            validatorInspector = inspector,
            frameDecodabilityVerifier = decodabilityVerifier
        )
    }

    companion object {
        const val KEY_JOB_ID = "jobId"

        @Volatile
        var testDatabaseOverride: StudioDatabase? = null

        @Volatile
        var testBaseFilesDirOverride: File? = null

        @Volatile
        var testInspectorOverride: VideoContainerInspector? = null

        @Volatile
        var testDecodabilityVerifierOverride: FrameDecodabilityVerifier? = null

        fun clearTestOverrides() {
            testDatabaseOverride = null
            testBaseFilesDirOverride = null
            testInspectorOverride = null
            testDecodabilityVerifierOverride = null
        }

        suspend fun executeForJob(
            context: Context,
            jobId: String,
            database: StudioDatabase,
            baseFilesDir: File = context.filesDir,
            validatorInspector: VideoContainerInspector = MediaAssetValidator.defaultInspector,
            frameDecodabilityVerifier: FrameDecodabilityVerifier = MediaAssetValidator.defaultFrameDecodabilityVerifier
        ): Result {
            val jobDao = database.generationJobDao()
            val job = jobDao.getJob(jobId)
                ?: return Result.failure(workDataOf("error" to "Missing job row for $jobId"))

            if (job.jobKind != GenerationJobKind.LOCAL_IMPORT) {
                return Result.failure(workDataOf("error" to "Wrong worker for jobKind=${job.jobKind}"))
            }

            val existingAssetId = job.resultAssetId
            if (job.status == GenerationJobStatus.COMPLETED && !existingAssetId.isNullOrBlank()) {
                val manifest = database.assetManifestDao().getByAssetId(existingAssetId)
                val promotedFile = File(baseFilesDir, "studio_assets/$existingAssetId.mp4")
                if (manifest != null && promotedFile.exists() && promotedFile.length() == manifest.byteSize) {
                    return Result.success(workDataOf(KEY_JOB_ID to jobId))
                }
            }

            if (job.status == GenerationJobStatus.CANCELLED ||
                job.status == GenerationJobStatus.FAILED ||
                job.status == GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED
            ) {
                return Result.failure(
                    workDataOf(
                        KEY_JOB_ID to jobId,
                        "error" to (job.errorMessage ?: "Job already in terminal state ${job.status}")
                    )
                )
            }

            val sourceUriString = job.sourceUri
            if (sourceUriString.isNullOrBlank()) {
                val msg = "Missing durable sourceUri for local import job $jobId"
                jobDao.transitionStatus(
                    jobId = jobId,
                    targetStatus = GenerationJobStatus.FAILED,
                    errorMessage = msg
                )
                return Result.failure(workDataOf("error" to msg))
            }

            val assetManager = AssetManager(
                baseFilesDir = baseFilesDir,
                database = database,
                validatorInspector = validatorInspector,
                frameDecodabilityVerifier = frameDecodabilityVerifier
            )

            return assetManager.ingestLocalMp4ForJob(
                jobId = jobId,
                sceneId = job.sceneId,
                sourceUri = Uri.parse(sourceUriString),
                context = context
            ).fold(
                onSuccess = {
                    Result.success(workDataOf(KEY_JOB_ID to jobId))
                },
                onFailure = { error ->
                    Result.failure(
                        workDataOf(
                            KEY_JOB_ID to jobId,
                            "error" to (error.message ?: "Local import failed")
                        )
                    )
                }
            )
        }
    }
}
