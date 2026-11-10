package com.example.studio.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationJobDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJobIfAbsent(
        job: GenerationJobEntity
    ): Long

    @Query("SELECT * FROM generation_jobs WHERE jobId = :jobId")
    suspend fun getJob(jobId: String): GenerationJobEntity?

    @Query("SELECT * FROM generation_jobs WHERE idempotencyKey = :key")
    suspend fun getByIdempotencyKey(key: String): GenerationJobEntity?

    @Query("SELECT * FROM generation_jobs WHERE status IN (:statuses)")
    suspend fun getJobsByStatuses(
        statuses: List<GenerationJobStatus>
    ): List<GenerationJobEntity>

    @Query("SELECT * FROM generation_jobs WHERE sceneId = :sceneId ORDER BY createdAtEpochMs DESC")
    fun observeJobsForScene(sceneId: String): Flow<List<GenerationJobEntity>>

    @Query(
        """
        UPDATE generation_jobs
        SET status = :newStatus,
            providerOperationId = COALESCE(:providerOperationId, providerOperationId),
            submissionAttemptedAtMs = COALESCE(:submissionAttemptedAtMs, submissionAttemptedAtMs),
            resultAssetId = COALESCE(:resultAssetId, resultAssetId),
            errorMessage = :errorMessage,
            attemptCount = :attemptCount,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE jobId = :jobId AND status = :expectedCurrentStatus
        """
    )
    suspend fun compareAndSetStatusInternal(
        jobId: String,
        expectedCurrentStatus: GenerationJobStatus,
        newStatus: GenerationJobStatus,
        providerOperationId: String?,
        submissionAttemptedAtMs: Long?,
        resultAssetId: String?,
        errorMessage: String?,
        attemptCount: Int,
        updatedAtEpochMs: Long
    ): Int

    /**
     * Transactional status update that verifies the previous state and central transition
     * table (`GenerationJobStatus.isValidTransition`) — enforcing `jobKind`-specific rules —
     * before mutating the row.
     */
    @Transaction
    suspend fun transitionStatus(
        jobId: String,
        targetStatus: GenerationJobStatus,
        providerOperationId: String? = null,
        submissionAttemptedAtMs: Long? = null,
        resultAssetId: String? = null,
        errorMessage: String? = null,
        incrementAttempt: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = getJob(jobId) ?: return false
        if (!GenerationJobStatus.isValidTransition(current.status, targetStatus, current.jobKind)) {
            return false
        }
        val nextAttempt = if (incrementAttempt) current.attemptCount + 1 else current.attemptCount
        val rows = compareAndSetStatusInternal(
            jobId = jobId,
            expectedCurrentStatus = current.status,
            newStatus = targetStatus,
            providerOperationId = providerOperationId,
            submissionAttemptedAtMs = submissionAttemptedAtMs,
            resultAssetId = resultAssetId,
            errorMessage = errorMessage ?: current.errorMessage,
            attemptCount = nextAttempt,
            updatedAtEpochMs = nowEpochMs
        )
        return rows == 1
    }

    /**
     * Resolves any interrupted `SUBMITTING` provider job that has no persisted `providerOperationId`
     * into `AMBIGUOUS_SUBMISSION_FAILED` so WorkManager never repeats an ambiguous billable POST.
     */
    @Transaction
    suspend fun markInterruptedSubmittingJobAmbiguous(
        jobId: String,
        reason: String = "Interrupted during SUBMITTING without persisted providerOperationId; automatic retry blocked to prevent duplicate billing.",
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = getJob(jobId) ?: return false
        if (current.jobKind == GenerationJobKind.LOCAL_IMPORT) return false
        if (current.status != GenerationJobStatus.SUBMITTING) return false
        if (!current.providerOperationId.isNullOrBlank()) return false
        return transitionStatus(
            jobId = jobId,
            targetStatus = GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED,
            errorMessage = reason,
            nowEpochMs = nowEpochMs
        )
    }
}
