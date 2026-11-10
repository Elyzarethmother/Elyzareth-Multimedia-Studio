package com.example.studio.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable promotion journal recording exact `(jobId, assetId)` ownership before atomic
 * filesystem promotion. Eliminates any filename or scene-based orphan guessing during recovery.
 */
@Entity(
    tableName = "promotion_journal",
    indices = [
        Index(value = ["jobId"], unique = true),
        Index(value = ["assetId"], unique = true)
    ]
)
data class PromotionJournalEntity(
    @PrimaryKey
    val journalId: String,

    val jobId: String,
    val assetId: String,

    val stagingRelativePath: String,
    val finalRelativePath: String,
    val expectedSha256Hex: String,

    val phase: PromotionPhase,

    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
