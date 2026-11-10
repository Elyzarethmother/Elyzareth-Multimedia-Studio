package com.example.studio.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GenerationJobEntity::class,
        AssetManifestEntity::class,
        PromotionJournalEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(GenerationJobStatusConverter::class)
abstract class StudioDatabase : RoomDatabase() {

    abstract fun generationJobDao(): GenerationJobDao
    abstract fun assetManifestDao(): AssetManifestDao
    abstract fun promotionJournalDao(): PromotionJournalDao

    /**
     * Executes [block] inside a genuine SQLite transaction via `RoomDatabase.withTransaction`.
     * Can be overridden only by pure in-memory JVM test doubles that do not wrap a SQLiteOpenHelper.
     */
    protected open suspend fun <R> executeInSqliteTransaction(block: suspend () -> R): R {
        return withTransaction {
            block()
        }
    }

    /**
     * Atomically commits a promoted asset in one `RoomDatabase.withTransaction` block:
     * 1. Loads the exact [GenerationJobEntity] and [PromotionJournalEntity].
     * 2. Verifies journal ownership (`journal.jobId == jobId`, `journal.assetId == manifest.assetId`)
     *    and verifies `job.status == GenerationJobStatus.VALIDATING`.
     * 3. Inserts the immutable [AssetManifestEntity] if absent, rejecting any `assetId`/SHA-256 collision.
     * 4. Transitions the exact job to `COMPLETED` with `resultAssetId = manifest.assetId`.
     * 5. Marks the promotion journal `COMMITTED`.
     *
     * If any step or compare-and-set check fails, throws an [IllegalStateException] so
     * `withTransaction` aborts and rolls back the entire SQLite transaction.
     */
    open suspend fun commitPromotedAsset(
        jobId: String,
        manifest: AssetManifestEntity,
        journalId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = executeInSqliteTransaction {
        val jobDao = generationJobDao()
        val assetDao = assetManifestDao()
        val journalDao = promotionJournalDao()

        val job = jobDao.getJob(jobId)
            ?: error("Missing generation job: $jobId")
        val journal = journalDao.getById(journalId)
            ?: error("Missing promotion journal: $journalId")

        check(journal.jobId == jobId) {
            "Promotion journal owner mismatch: expected $jobId, found ${journal.jobId}"
        }
        check(journal.assetId == manifest.assetId) {
            "Promotion journal asset mismatch: expected ${manifest.assetId}, found ${journal.assetId}"
        }
        check(journal.expectedSha256Hex.equals(manifest.sha256Hex, ignoreCase = true)) {
            "Promotion journal SHA-256 mismatch for ${manifest.assetId}"
        }
        check(job.status == GenerationJobStatus.VALIDATING) {
            "Unexpected job state ${job.status}; expected VALIDATING"
        }

        val existing = assetDao.getByAssetId(manifest.assetId)
        if (existing != null) {
            check(existing.sha256Hex.equals(manifest.sha256Hex, ignoreCase = true)) {
                "Immutable assetId collision: ${manifest.assetId}"
            }
        } else {
            val insertedRowId = assetDao.insertManifestIfAbsent(manifest)
            check(insertedRowId != -1L) {
                "Failed to insert manifest for ${manifest.assetId}"
            }
        }

        val updated = jobDao.transitionStatus(
            jobId = jobId,
            targetStatus = GenerationJobStatus.COMPLETED,
            resultAssetId = manifest.assetId,
            errorMessage = null,
            nowEpochMs = nowEpochMs
        )
        check(updated) { "Job compare-and-set failed; transaction must roll back" }

        val journalUpdated = journalDao.markCommitted(
            journalId = journalId,
            committedAtEpochMs = nowEpochMs
        )
        check(journalUpdated) { "Promotion journal commit failed; transaction must roll back" }

        true
    }

    /**
     * Convenience wrapper that ensures a promotion journal exists in `PROMOTED` state for
     * `(jobId, manifest.assetId)` and delegates to [commitPromotedAsset].
     */
    open suspend fun commitAssetManifestAndCompleteJob(
        manifest: AssetManifestEntity,
        jobId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = executeInSqliteTransaction {
        val journalDao = promotionJournalDao()
        val existingJournal = journalDao.getByJobId(jobId)
        val journalId = existingJournal?.journalId ?: "journal_${jobId}_${manifest.assetId}"
        if (existingJournal == null) {
            journalDao.insertJournalIfAbsent(
                PromotionJournalEntity(
                    journalId = journalId,
                    jobId = jobId,
                    assetId = manifest.assetId,
                    stagingRelativePath = ".staging_tmp/job_$jobId.part",
                    finalRelativePath = manifest.relativePath,
                    expectedSha256Hex = manifest.sha256Hex,
                    phase = PromotionPhase.PROMOTED,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs
                )
            )
        } else if (existingJournal.phase == PromotionPhase.PREPARED) {
            journalDao.markPromoted(journalId, nowEpochMs)
        }
        commitPromotedAsset(
            jobId = jobId,
            manifest = manifest,
            journalId = journalId,
            nowEpochMs = nowEpochMs
        )
    }

    companion object {
        private const val DATABASE_NAME = "music_video_studio_assets.db"

        /**
         * Non-destructive forward migration from Room schema v1 to v2:
         * - Adds `jobKind`, `sourceUri`, and `sourceUriGrantFlags` to `generation_jobs`.
         * - Creates `promotion_journal` and its unique indices on `jobId` and `assetId`.
         * Never uses `fallbackToDestructiveMigration()`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `generation_jobs` ADD COLUMN `jobKind` TEXT NOT NULL DEFAULT 'PROVIDER_GENERATION'"
                )
                db.execSQL(
                    "ALTER TABLE `generation_jobs` ADD COLUMN `sourceUri` TEXT"
                )
                db.execSQL(
                    "ALTER TABLE `generation_jobs` ADD COLUMN `sourceUriGrantFlags` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `promotion_journal` (
                        `journalId` TEXT NOT NULL,
                        `jobId` TEXT NOT NULL,
                        `assetId` TEXT NOT NULL,
                        `stagingRelativePath` TEXT NOT NULL,
                        `finalRelativePath` TEXT NOT NULL,
                        `expectedSha256Hex` TEXT NOT NULL,
                        `phase` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`journalId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_promotion_journal_jobId` ON `promotion_journal` (`jobId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_promotion_journal_assetId` ON `promotion_journal` (`assetId`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: StudioDatabase? = null

        fun getInstance(context: Context): StudioDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudioDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
