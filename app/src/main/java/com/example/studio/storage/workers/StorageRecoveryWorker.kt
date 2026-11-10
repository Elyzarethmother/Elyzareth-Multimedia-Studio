package com.example.studio.storage.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.studio.ingestion.StorageReconciliationManager
import com.example.studio.storage.db.StudioDatabase

/**
 * Startup & periodic background recovery worker that reconciles orphaned promoted assets
 * in `filesDir/studio_assets/`, marks interrupted `SUBMITTING` jobs as `AMBIGUOUS_SUBMISSION_FAILED`,
 * and sweeps stale `.part` files from `filesDir/.staging_tmp/` without touching active jobs.
 *
 * Requires NO network connectivity constraint as it operates strictly on local storage and Room.
 */
class StorageRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = StudioDatabase.getInstance(applicationContext)
            val reconciler = StorageReconciliationManager(
                baseFilesDir = applicationContext.filesDir,
                database = database
            )
            reconciler.reconcileStorageAndDatabase()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
