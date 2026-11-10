package com.example.studio.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "generation_jobs",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["sceneId"]),
        Index(value = ["status"])
    ]
)
data class GenerationJobEntity(
    @PrimaryKey
    val jobId: String,

    val idempotencyKey: String,
    val sceneId: String,
    val providerId: String,
    val modelId: String,
    val prompt: String,

    val status: GenerationJobStatus,

    val providerOperationId: String? = null,
    val submissionAttemptedAtMs: Long? = null,

    val resultAssetId: String? = null,
    val errorMessage: String? = null,

    val attemptCount: Int = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,

    @ColumnInfo(defaultValue = "'PROVIDER_GENERATION'")
    val jobKind: GenerationJobKind = GenerationJobKind.PROVIDER_GENERATION,

    val sourceUri: String? = null,

    @ColumnInfo(defaultValue = "0")
    val sourceUriGrantFlags: Int = 0
)
