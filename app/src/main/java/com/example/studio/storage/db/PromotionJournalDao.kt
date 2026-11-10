package com.example.studio.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PromotionJournalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJournalIfAbsent(journal: PromotionJournalEntity): Long

    @Query("SELECT * FROM promotion_journal WHERE journalId = :journalId")
    suspend fun getById(journalId: String): PromotionJournalEntity?

    @Query("SELECT * FROM promotion_journal WHERE jobId = :jobId")
    suspend fun getByJobId(jobId: String): PromotionJournalEntity?

    @Query("SELECT * FROM promotion_journal WHERE assetId = :assetId")
    suspend fun getByAssetId(assetId: String): PromotionJournalEntity?

    @Query("SELECT * FROM promotion_journal ORDER BY createdAtEpochMs ASC")
    suspend fun getAllJournals(): List<PromotionJournalEntity>

    @Query("SELECT * FROM promotion_journal WHERE phase IN (:phases) ORDER BY createdAtEpochMs ASC")
    suspend fun getByPhases(phases: List<PromotionPhase>): List<PromotionJournalEntity>

    @Query(
        """
        UPDATE promotion_journal
        SET phase = :newPhase,
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE journalId = :journalId AND phase = :expectedCurrentPhase
        """
    )
    suspend fun compareAndSetPhaseInternal(
        journalId: String,
        expectedCurrentPhase: PromotionPhase,
        newPhase: PromotionPhase,
        updatedAtEpochMs: Long
    ): Int

    @Transaction
    suspend fun markPromoted(
        journalId: String,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = getById(journalId) ?: return false
        if (current.phase == PromotionPhase.PROMOTED) return true
        if (current.phase != PromotionPhase.PREPARED) return false
        return compareAndSetPhaseInternal(
            journalId = journalId,
            expectedCurrentPhase = PromotionPhase.PREPARED,
            newPhase = PromotionPhase.PROMOTED,
            updatedAtEpochMs = updatedAtEpochMs
        ) == 1
    }

    @Transaction
    suspend fun markCommitted(
        journalId: String,
        committedAtEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val current = getById(journalId) ?: return false
        if (current.phase == PromotionPhase.COMMITTED) return true
        if (current.phase != PromotionPhase.PROMOTED) return false
        return compareAndSetPhaseInternal(
            journalId = journalId,
            expectedCurrentPhase = PromotionPhase.PROMOTED,
            newPhase = PromotionPhase.COMMITTED,
            updatedAtEpochMs = committedAtEpochMs
        ) == 1
    }

    @Query("DELETE FROM promotion_journal WHERE journalId = :journalId")
    suspend fun deleteById(journalId: String): Int
}
