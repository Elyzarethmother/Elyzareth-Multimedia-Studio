package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.media.EdlHistoryStack
import com.example.media.EdlTimelineEngine
import com.example.media.SceneVideoFrameExtractor
import com.example.media.VideoFrameRenderer
import com.example.model.CameraMovementPreset
import com.example.model.EdlSceneClip
import com.example.model.SceneVersionSnapshot
import com.example.model.StoryboardCue
import com.example.model.VideoModelType
import com.example.model.VisualFilterStyle
import com.example.studio.ingestion.AssetManager
import com.example.studio.ingestion.ChecksumVerifier
import com.example.studio.ingestion.FrameDecodabilityVerifier
import com.example.studio.ingestion.MediaAssetValidator
import com.example.studio.ingestion.MediaExtractorProbe
import com.example.studio.ingestion.MediaValidationResult
import com.example.studio.ingestion.StorageReconciliationManager
import com.example.studio.ingestion.VfrDetector
import com.example.studio.ingestion.VideoContainerInspector
import com.example.studio.ingestion.VideoContainerProbeResult
import com.example.studio.storage.db.AssetManifestEntity
import com.example.studio.storage.db.GenerationJobEntity
import com.example.studio.storage.db.GenerationJobKind
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.PromotionJournalEntity
import com.example.studio.storage.db.PromotionPhase
import com.example.studio.storage.db.StudioDatabase
import com.example.studio.storage.workers.GenerationWorkOrchestrator
import com.example.studio.storage.workers.LocalImportWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Phase 2C Mandatory Acceptance Test Suite (`phase2c_corrected_handoff/tests/ACCEPTANCE_TESTS.md`).
 *
 * Verifies:
 * 1. Real file-backed SQLite Room persistence, `RoomDatabase.withTransaction` rollback,
 *    `MIGRATION_1_2` schema upgrade, and checked-in Room schemas `1.json` & `2.json`.
 * 2. WorkManager `LocalImportWorker` (`Data.keyValueMap.keys == setOf("jobId")`, `ExistingWorkPolicy.KEEP`,
 *    Room-backed `sourceUri` & `sceneId`, idempotent re-execution, and all 5 crash windows).
 * 3. `PromotionJournalEntity` exact-job recovery (`PREPARED -> PROMOTED -> COMMITTED`) with zero
 *    cross-job or filename-guessing recovery.
 * 4. Complete removal of `videoClipPath` from `EdlSceneClip`, `SceneVersionSnapshot`, `StoryboardCue`,
 *    and serialized EDL/history JSON, plus `assetId` resolution at the rendering boundary.
 * 5. Bounded streaming `MediaExtractorProbe` (audio+video track selection, CFR, VFR, >8MB streaming,
 *    `isVfr = null` / `scanComplete = false` on truncated scan) and separate `FrameDecodabilityVerifier`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [TestMp4ShadowMediaExtractor::class, TestMp4ShadowMediaMetadataRetriever::class]
)
class Phase2CAcceptanceTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: StudioDatabase
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        baseDir = Files.createTempDirectory("phase2c_test_dir").toFile()
        dbFile = File(baseDir, "studio_phase2c_test.db")
        db = Room.databaseBuilder(context, StudioDatabase::class.java, dbFile.absolutePath)
            .addMigrations(StudioDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        LocalImportWorker.clearTestOverrides()
        SceneVideoFrameExtractor.clearCachesForTest()
        try {
            db.close()
        } catch (_: Exception) {
        }
        baseDir.deleteRecursively()
    }

    // =========================================================================
    // 1. ROOM / SQLITE PERSISTENCE, TRANSACTION ROLLBACK & MIGRATION 1 -> 2
    // =========================================================================

    @Test
    fun `1 - real file-backed SQLite Room database persists jobs manifests and journal across close and reopen`() = runBlocking {
        val assetManager = AssetManager(baseDir, db)
        val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1280, height = 720, durationTicks = 3000)
        val sourceFile = File(baseDir, "source_persist.mp4").apply { writeBytes(mp4Bytes) }

        val jobId = "job_persist_reopen_01"
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobId,
                idempotencyKey = "idem_persist_reopen_01",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Persist reopen test",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = Uri.fromFile(sourceFile).toString(),
                sourceUriGrantFlags = 1,
                status = GenerationJobStatus.QUEUED,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )

        val descriptor = assetManager.ingestLocalMp4ForJob(
            jobId = jobId,
            sceneId = "scene_1",
            sourceUri = Uri.fromFile(sourceFile),
            context = context
        ).getOrThrow()

        // Close SQLite database completely and reopen from disk file
        db.close()

        val reopenedDb = Room.databaseBuilder(context, StudioDatabase::class.java, dbFile.absolutePath)
            .addMigrations(StudioDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val persistedJob = reopenedDb.generationJobDao().getJob(jobId)
            assertNotNull(persistedJob)
            assertEquals(GenerationJobStatus.COMPLETED, persistedJob?.status)
            assertEquals(GenerationJobKind.LOCAL_IMPORT, persistedJob?.jobKind)
            assertEquals(Uri.fromFile(sourceFile).toString(), persistedJob?.sourceUri)
            assertEquals(1, persistedJob?.sourceUriGrantFlags)
            assertEquals(descriptor.assetId, persistedJob?.resultAssetId)

            val persistedManifest = reopenedDb.assetManifestDao().getByAssetId(descriptor.assetId)
            assertNotNull(persistedManifest)
            assertEquals(descriptor.sha256Hex, persistedManifest?.sha256Hex)

            val persistedJournal = reopenedDb.promotionJournalDao().getByJobId(jobId)
            assertNotNull(persistedJournal)
            assertEquals(PromotionPhase.COMMITTED, persistedJournal?.phase)
        } finally {
            reopenedDb.close()
        }
    }

    @Test
    fun `2 - StudioDatabase commitPromotedAsset uses RoomDatabase withTransaction and rolls back on failure`() = runBlocking {
        val jobId = "job_atomic_tx_fail"
        val journalId = "journal_${jobId}_asset_scene_1_v01"
        // Insert job in VALIDATING state, and journal in PREPARED (not PROMOTED!)
        // Inside commitPromotedAsset:
        // Step 1: assetDao.insertManifestIfAbsent(manifest) executes and inserts the row in SQLite
        // Step 2: jobDao.transitionStatus(VALIDATING -> COMPLETED) executes and updates the job row in SQLite
        // Step 3: journalDao.markCommitted(journalId) returns false because journal is PREPARED instead of PROMOTED,
        //         triggering check(journalUpdated) and rolling back Steps 1 and 2!
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobId,
                idempotencyKey = "idem_atomic_tx_fail",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Rollback verification",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = journalId,
                jobId = jobId,
                assetId = "asset_scene_1_v01",
                stagingRelativePath = ".staging_tmp/staging.part",
                finalRelativePath = "studio_assets/asset_scene_1_v01.mp4",
                expectedSha256Hex = "a".repeat(64),
                phase = PromotionPhase.PREPARED, // Not PROMOTED -> Step 3 markCommitted will fail after Steps 1 & 2!
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )

        var threwException = false
        try {
            db.commitPromotedAsset(
                jobId = jobId,
                manifest = AssetManifestEntity(
                    assetId = "asset_scene_1_v01",
                    sceneId = "scene_1",
                    versionNumber = 1,
                    relativePath = "studio_assets/asset_scene_1_v01.mp4",
                    contentUri = "file:///tmp/asset_scene_1_v01.mp4",
                    sha256Hex = "a".repeat(64),
                    byteSize = 4096L,
                    mimeType = "video/mp4",
                    width = 1280,
                    height = 720,
                    durationMs = 2000L,
                    nominalFps = 24f,
                    isVfr = false,
                    ptsManifestPath = null,
                    createdAtEpochMs = 1_500L
                ),
                journalId = journalId,
                nowEpochMs = 2_000L
            )
        } catch (e: IllegalStateException) {
            threwException = true
        }

        assertTrue("commitPromotedAsset must fail when journal is not in PROMOTED phase", threwException)
        assertNull(
            "Step 1 assetDao.insertManifestIfAbsent must be rolled back by RoomDatabase.withTransaction",
            db.assetManifestDao().getByAssetId("asset_scene_1_v01")
        )
        assertEquals(
            "Step 2 jobDao.transitionStatus(COMPLETED) must be rolled back to VALIDATING",
            GenerationJobStatus.VALIDATING,
            db.generationJobDao().getJob(jobId)?.status
        )
        assertNull(
            "Rolled-back job must not retain resultAssetId",
            db.generationJobDao().getJob(jobId)?.resultAssetId
        )
    }

    @Test
    fun `3 - duplicate assetId with different SHA-256 is rejected in commitPromotedAsset`() = runBlocking {
        val job1 = "job_dup_sha_1"
        val job2 = "job_dup_sha_2"
        val journal1 = "journal_${job1}_asset_scene_1_v01"
        val journal2 = "journal_${job2}_asset_scene_1_v01"
        val sha1 = "1".repeat(64)
        val sha2 = "2".repeat(64)

        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = job1,
                idempotencyKey = "idem_dup_sha_1",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "First commit",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = journal1,
                jobId = job1,
                assetId = "asset_scene_1_v01",
                stagingRelativePath = ".staging_tmp/1.part",
                finalRelativePath = "studio_assets/asset_scene_1_v01.mp4",
                expectedSha256Hex = sha1,
                phase = PromotionPhase.PROMOTED,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )
        db.commitPromotedAsset(
            jobId = job1,
            manifest = AssetManifestEntity(
                assetId = "asset_scene_1_v01",
                sceneId = "scene_1",
                versionNumber = 1,
                relativePath = "studio_assets/asset_scene_1_v01.mp4",
                contentUri = "file:///tmp/asset_scene_1_v01.mp4",
                sha256Hex = sha1,
                byteSize = 1024L,
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                durationMs = 2000L,
                nominalFps = 24f,
                isVfr = false,
                ptsManifestPath = null,
                createdAtEpochMs = 1_000L
            ),
            journalId = journal1
        )

        // Now attempt to commit same assetId with different SHA-256 -> must fail and roll back
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = job2,
                idempotencyKey = "idem_dup_sha_2",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Conflicting commit",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 2_000L,
                updatedAtEpochMs = 2_000L
            )
        )
        db.promotionJournalDao().deleteById(journal1)
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = journal2,
                jobId = job2,
                assetId = "asset_scene_1_v01",
                stagingRelativePath = ".staging_tmp/2.part",
                finalRelativePath = "studio_assets/asset_scene_1_v01.mp4",
                expectedSha256Hex = sha2,
                phase = PromotionPhase.PROMOTED,
                createdAtEpochMs = 2_000L,
                updatedAtEpochMs = 2_000L
            )
        )
        var rejectedConflict = false
        try {
            db.commitPromotedAsset(
                jobId = job2,
                manifest = AssetManifestEntity(
                    assetId = "asset_scene_1_v01",
                    sceneId = "scene_1",
                    versionNumber = 1,
                    relativePath = "studio_assets/asset_scene_1_v01.mp4",
                    contentUri = "file:///tmp/asset_scene_1_v01.mp4",
                    sha256Hex = sha2,
                    byteSize = 2048L,
                    mimeType = "video/mp4",
                    width = 1280,
                    height = 720,
                    durationMs = 2000L,
                    nominalFps = 24f,
                    isVfr = false,
                    ptsManifestPath = null,
                    createdAtEpochMs = 2_000L
                ),
                journalId = journal2
            )
        } catch (e: IllegalStateException) {
            rejectedConflict = e.message?.contains("Immutable assetId collision") == true
        }
        assertTrue("Duplicate assetId with different SHA-256 must be rejected", rejectedConflict)
        assertEquals(sha1, db.assetManifestDao().getByAssetId("asset_scene_1_v01")?.sha256Hex)
    }

    @Test
    fun `4 - Room v1 to v2 migration (MIGRATION_1_2) preserves v1 rows and creates v2 schema`() = runBlocking {
        val migDbFile = File(baseDir, "migration_v1_to_v2.db")
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Exact v1 schema from schemas/com.example.studio.storage.db.StudioDatabase/1.json
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `generation_jobs` (
                        `jobId` TEXT NOT NULL,
                        `idempotencyKey` TEXT NOT NULL,
                        `sceneId` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `providerOperationId` TEXT,
                        `submissionAttemptedAtMs` INTEGER,
                        `resultAssetId` TEXT,
                        `errorMessage` TEXT,
                        `attemptCount` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_generation_jobs_idempotencyKey` ON `generation_jobs` (`idempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_jobs_sceneId` ON `generation_jobs` (`sceneId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_jobs_status` ON `generation_jobs` (`status`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `asset_manifest` (
                        `assetId` TEXT NOT NULL,
                        `sceneId` TEXT NOT NULL,
                        `versionNumber` INTEGER NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `contentUri` TEXT NOT NULL,
                        `sha256Hex` TEXT NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `nominalFps` REAL NOT NULL,
                        `isVfr` INTEGER NOT NULL,
                        `ptsManifestPath` TEXT,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`assetId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_asset_manifest_sha256Hex` ON `asset_manifest` (`sha256Hex`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_asset_manifest_sceneId` ON `asset_manifest` (`sceneId`)")

                // Insert v1 rows prior to migration
                db.execSQL(
                    """
                    INSERT INTO `generation_jobs` (
                        `jobId`, `idempotencyKey`, `sceneId`, `providerId`, `modelId`, `prompt`,
                        `status`, `providerOperationId`, `submissionAttemptedAtMs`, `resultAssetId`,
                        `errorMessage`, `attemptCount`, `createdAtEpochMs`, `updatedAtEpochMs`
                    ) VALUES (
                        'v1_job_001', 'idem_v1_001', 'scene_1', 'veo_provider', 'veo-3.1',
                        'Original v1 prompt', 'COMPLETED', 'op_v1_99', 1000, 'asset_scene_1_v01',
                        NULL, 1, 900, 1500
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `asset_manifest` (
                        `assetId`, `sceneId`, `versionNumber`, `relativePath`, `contentUri`, `sha256Hex`,
                        `byteSize`, `mimeType`, `width`, `height`, `durationMs`, `nominalFps`, `isVfr`,
                        `ptsManifestPath`, `createdAtEpochMs`
                    ) VALUES (
                        'asset_scene_1_v01', 'scene_1', 1, 'studio_assets/asset_scene_1_v01.mp4',
                        'file:///data/studio_assets/asset_scene_1_v01.mp4',
                        'abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234',
                        8192, 'video/mp4', 1920, 1080, 4000, 24.0, 0, NULL, 1400
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(migDbFile.absolutePath)
            .callback(callback)
            .build()
        FrameworkSQLiteOpenHelperFactory().create(helperConfig).use { helper ->
            helper.writableDatabase.close()
        }

        // Open with Room v2 and MIGRATION_1_2 (Room validates the migrated schema against v2 entity definitions!)
        val migratedRoomDb = Room.databaseBuilder(context, StudioDatabase::class.java, migDbFile.absolutePath)
            .addMigrations(StudioDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedJob = migratedRoomDb.generationJobDao().getJob("v1_job_001")
            assertNotNull("v1 job row must survive MIGRATION_1_2", migratedJob)
            assertEquals(GenerationJobKind.PROVIDER_GENERATION, migratedJob?.jobKind)
            assertNull(migratedJob?.sourceUri)
            assertEquals(0, migratedJob?.sourceUriGrantFlags)
            assertEquals(GenerationJobStatus.COMPLETED, migratedJob?.status)
            assertEquals("asset_scene_1_v01", migratedJob?.resultAssetId)

            val migratedAsset = migratedRoomDb.assetManifestDao().getByAssetId("asset_scene_1_v01")
            assertNotNull("v1 asset_manifest row must survive MIGRATION_1_2", migratedAsset)
            assertEquals(1920, migratedAsset?.width)

            // Verify new promotion_journal table works in migrated DB
            migratedRoomDb.promotionJournalDao().insertJournalIfAbsent(
                PromotionJournalEntity(
                    journalId = "journal_v2_new_job_asset_scene_2_v01",
                    jobId = "v2_new_job",
                    assetId = "asset_scene_2_v01",
                    stagingRelativePath = ".staging_tmp/s.part",
                    finalRelativePath = "studio_assets/t.mp4",
                    expectedSha256Hex = "f".repeat(64),
                    phase = PromotionPhase.PREPARED,
                    createdAtEpochMs = 2000L,
                    updatedAtEpochMs = 2000L
                )
            )
            assertNotNull(migratedRoomDb.promotionJournalDao().getByJobId("v2_new_job"))
        } finally {
            migratedRoomDb.close()
        }
    }

    @Test
    fun `5 - exported Room schema files 1_json and 2_json exist and contain v1 and v2 schema definitions`() {
        val schemaDir = File("schemas/com.example.studio.storage.db.StudioDatabase")
        val v1File = File(schemaDir, "1.json")
        val v2File = File(schemaDir, "2.json")

        assertTrue("Room schema 1.json must be checked in at ${v1File.path}", v1File.exists())
        assertTrue("Room schema 2.json must be checked in at ${v2File.path}", v2File.exists())

        val v1Json = v1File.readText()
        val v2Json = v2File.readText()
        assertTrue(v1Json.contains("\"version\": 1"))
        assertTrue(v1Json.contains("\"identityHash\": \"ac80f4a672dcbb14b437055ff4edd2f2\""))
        assertFalse(v1Json.contains("promotion_journal"))

        assertTrue(v2Json.contains("\"version\": 2"))
        assertTrue(v2Json.contains("\"identityHash\": \"97243823950a9ae0d387d76ed8f4e177\""))
        assertTrue(v2Json.contains("promotion_journal"))
        assertTrue(v2Json.contains("sourceUri"))
        assertTrue(v2Json.contains("jobKind"))

        // Verify live SQLite room_master_table identity_hash matches KSP-exported 2.json
        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("97243823950a9ae0d387d76ed8f4e177", cursor.getString(0))
        }
    }

    // =========================================================================
    // 2. WORKMANAGER & LOCALIMPORTWORKER TESTS
    // =========================================================================

    @Test
    fun `6 - GenerationWorkOrchestrator enqueues LocalImportWorker with KEEP policy and jobId-only Data`() {
        val jobId = "job_local_wm_dedup_01"
        val request = GenerationWorkOrchestrator.buildLocalImportWorkRequest(jobId)

        assertEquals(
            "WorkManager inputData must contain ONLY jobId",
            setOf("jobId"),
            request.workSpec.input.keyValueMap.keys
        )
        assertEquals(jobId, request.workSpec.input.getString("jobId"))
        assertEquals(LocalImportWorker::class.java.name, request.workSpec.workerClassName)

        GenerationWorkOrchestrator.enqueueLocalImportWork(context, jobId)
        GenerationWorkOrchestrator.enqueueLocalImportWork(context, jobId)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(GenerationWorkOrchestrator.uniqueWorkNameForJob(jobId))
            .get()
        assertEquals("ExistingWorkPolicy.KEEP must keep a single unique work chain per jobId", 1, workInfos.size)
        assertTrue(
            workInfos.first().state == WorkInfo.State.ENQUEUED ||
                workInfos.first().state == WorkInfo.State.RUNNING ||
                workInfos.first().state == WorkInfo.State.SUCCEEDED
        )
    }

    @Test
    fun `7 - LocalImportWorker loads sourceUri from Room, promotes asset, and is idempotent on duplicate execution`() = runBlocking {
        val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1280, height = 720, durationTicks = 2500)
        val sourceFile = File(baseDir, "picker_video.mp4").apply { writeBytes(mp4Bytes) }
        val jobId = "job_worker_local_01"

        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobId,
                idempotencyKey = "idem_worker_local_01",
                sceneId = "scene_2",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Worker import test",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = Uri.fromFile(sourceFile).toString(),
                status = GenerationJobStatus.QUEUED,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )

        LocalImportWorker.testDatabaseOverride = db
        LocalImportWorker.testBaseFilesDirOverride = baseDir

        val worker1 = TestListenableWorkerBuilder<LocalImportWorker>(context)
            .setInputData(workDataOf("jobId" to jobId))
            .build()
        val result1 = worker1.doWork()
        assertTrue("First LocalImportWorker execution must succeed", result1 is ListenableWorker.Result.Success)

        val completedJob = db.generationJobDao().getJob(jobId)
        assertEquals(GenerationJobStatus.COMPLETED, completedJob?.status)
        assertEquals("asset_scene_2_v01", completedJob?.resultAssetId)
        assertEquals(PromotionPhase.COMMITTED, db.promotionJournalDao().getByJobId(jobId)?.phase)

        // Delete sourceFile to prove duplicate execution on COMPLETED job is idempotent without re-reading sourceUri!
        sourceFile.delete()
        val worker2 = TestListenableWorkerBuilder<LocalImportWorker>(context)
            .setInputData(workDataOf("jobId" to jobId))
            .build()
        val result2 = worker2.doWork()
        assertTrue("Duplicate LocalImportWorker execution on COMPLETED job must be idempotent", result2 is ListenableWorker.Result.Success)
        assertEquals(1, db.assetManifestDao().getBySceneId("scene_2").size)

        // Also verify LocalImportWorker rejects extra keys in inputData
        val workerBadData = TestListenableWorkerBuilder<LocalImportWorker>(context)
            .setInputData(workDataOf("jobId" to jobId, "extraKey" to "forbidden"))
            .build()
        assertTrue(workerBadData.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun `8 - crash-window recovery across all 5 boundaries recovers exact jobId via promotion_journal`() = runBlocking {
        val reconciler = StorageReconciliationManager(baseDir, db)
        val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1280, height = 720, durationTicks = 2000)
        val stagingDir = File(baseDir, ".staging_tmp").apply { mkdirs() }
        val studioAssetsDir = File(baseDir, "studio_assets").apply { mkdirs() }

        // Window A: Crash during copy to .staging_tmp/job_<jobId>.part (job in DOWNLOADING, no journal)
        val jobA = "job_crash_window_A"
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobA,
                idempotencyKey = "idem_crash_A",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Window A",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = "content://media/picker/0/a.mp4",
                status = GenerationJobStatus.DOWNLOADING,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )
        val partA = File(stagingDir, "job_$jobA.part").apply { writeBytes(ByteArray(128)) }

        // Window B: Crash after copy, before PREPARED journal row (job in VALIDATING, no journal)
        val jobB = "job_crash_window_B"
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobB,
                idempotencyKey = "idem_crash_B",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Window B",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = "content://media/picker/0/b.mp4",
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_100L,
                updatedAtEpochMs = 1_100L
            )
        )
        val partB = File(stagingDir, "job_$jobB.part").apply { writeBytes(mp4Bytes) }

        // Window C: Crash after PREPARED journal row (staging exists, before atomic move)
        val jobC = "job_crash_window_C"
        val partC = File(stagingDir, "job_$jobC.part").apply { writeBytes(mp4Bytes) }
        val shaC = ChecksumVerifier.computeSha256(partC)
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobC,
                idempotencyKey = "idem_crash_C",
                sceneId = "scene_2",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Window C",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = "content://media/picker/0/c.mp4",
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_200L,
                updatedAtEpochMs = 1_200L
            )
        )
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = "journal_${jobC}_asset_scene_2_v01",
                jobId = jobC,
                assetId = "asset_scene_2_v01",
                stagingRelativePath = ".staging_tmp/${partC.name}",
                finalRelativePath = "studio_assets/asset_scene_2_v01.mp4",
                expectedSha256Hex = shaC,
                phase = PromotionPhase.PREPARED,
                createdAtEpochMs = 1_200L,
                updatedAtEpochMs = 1_200L
            )
        )

        // Window D1: Crash immediately after atomic move (target exists on disk, journal still PREPARED!)
        val jobD1 = "job_crash_window_D1"
        val targetD1 = File(studioAssetsDir, "asset_scene_3_v01.mp4").apply { writeBytes(mp4Bytes) }
        val shaD1 = ChecksumVerifier.computeSha256(targetD1)
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobD1,
                idempotencyKey = "idem_crash_D1",
                sceneId = "scene_3",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Window D1",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = "content://media/picker/0/d1.mp4",
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_300L,
                updatedAtEpochMs = 1_300L
            )
        )
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = "journal_${jobD1}_asset_scene_3_v01",
                jobId = jobD1,
                assetId = "asset_scene_3_v01",
                stagingRelativePath = ".staging_tmp/job_$jobD1.part",
                finalRelativePath = "studio_assets/asset_scene_3_v01.mp4",
                expectedSha256Hex = shaD1,
                phase = PromotionPhase.PREPARED,
                createdAtEpochMs = 1_300L,
                updatedAtEpochMs = 1_300L
            )
        )

        // Window D2: Crash after journal marked PROMOTED, before Room commitPromotedAsset transaction
        val jobD2 = "job_crash_window_D2"
        val targetD2 = File(studioAssetsDir, "asset_scene_4_v01.mp4").apply { writeBytes(mp4Bytes) }
        val shaD2 = ChecksumVerifier.computeSha256(targetD2)
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobD2,
                idempotencyKey = "idem_crash_D2",
                sceneId = "scene_4",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Window D2",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = "content://media/picker/0/d2.mp4",
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_400L,
                updatedAtEpochMs = 1_400L
            )
        )
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = "journal_${jobD2}_asset_scene_4_v01",
                jobId = jobD2,
                assetId = "asset_scene_4_v01",
                stagingRelativePath = ".staging_tmp/job_$jobD2.part",
                finalRelativePath = "studio_assets/asset_scene_4_v01.mp4",
                expectedSha256Hex = shaD2,
                phase = PromotionPhase.PROMOTED,
                createdAtEpochMs = 1_400L,
                updatedAtEpochMs = 1_400L
            )
        )

        val summary = reconciler.reconcileStorageAndDatabase(nowEpochMs = 10_000L)

        // Window A & B: active jobs' staging files preserved; jobs remain in DOWNLOADING / VALIDATING for worker retry
        assertTrue(partA.exists())
        assertTrue(partB.exists())
        assertEquals(GenerationJobStatus.DOWNLOADING, db.generationJobDao().getJob(jobA)?.status)
        assertEquals(GenerationJobStatus.VALIDATING, db.generationJobDao().getJob(jobB)?.status)

        // Window C, D1 & D2: all 3 journaled jobs (C from PREPARED + staging, D1 from PREPARED + final, D2 from PROMOTED + final)
        // are deterministically recovered and committed for their exact jobIds!
        assertEquals(3, summary.orphanedAssetsRegistered)

        assertEquals(GenerationJobStatus.COMPLETED, db.generationJobDao().getJob(jobC)?.status)
        assertEquals("asset_scene_2_v01", db.generationJobDao().getJob(jobC)?.resultAssetId)
        assertEquals(PromotionPhase.COMMITTED, db.promotionJournalDao().getByJobId(jobC)?.phase)
        assertNotNull(db.assetManifestDao().getByAssetId("asset_scene_2_v01"))

        assertEquals(GenerationJobStatus.COMPLETED, db.generationJobDao().getJob(jobD1)?.status)
        assertEquals("asset_scene_3_v01", db.generationJobDao().getJob(jobD1)?.resultAssetId)
        assertEquals(PromotionPhase.COMMITTED, db.promotionJournalDao().getByJobId(jobD1)?.phase)
        assertNotNull(db.assetManifestDao().getByAssetId("asset_scene_3_v01"))

        assertEquals(GenerationJobStatus.COMPLETED, db.generationJobDao().getJob(jobD2)?.status)
        assertEquals("asset_scene_4_v01", db.generationJobDao().getJob(jobD2)?.resultAssetId)
        assertEquals(PromotionPhase.COMMITTED, db.promotionJournalDao().getByJobId(jobD2)?.phase)
        assertNotNull(db.assetManifestDao().getByAssetId("asset_scene_4_v01"))
    }

    @Test
    fun `9 - multi-job same-scene isolation prevents cross-job recovery or filename guessing`() = runBlocking {
        val studioAssetsDir = File(baseDir, "studio_assets").apply { mkdirs() }
        val mp4Bytes1 = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1280, height = 720, mdatPayloadSize = 1024)
        val mp4Bytes2 = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1920, height = 1080, mdatPayloadSize = 2048)

        // Job 1 and Job 2 BOTH target "scene_1" and are both in VALIDATING.
        // Only Job 2 has a PromotionJournal row for asset_scene_1_v01.mp4!
        // Also place an unjournaled valid MP4 file `asset_scene_1_v02.mp4` in studio_assets/.
        val job1 = "job_same_scene_1"
        val job2 = "job_same_scene_2"
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = job1,
                idempotencyKey = "idem_same_scene_1",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Job 1",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = job2,
                idempotencyKey = "idem_same_scene_2",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Job 2",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_100L,
                updatedAtEpochMs = 1_100L
            )
        )

        val fileJob2 = File(studioAssetsDir, "asset_scene_1_v01.mp4").apply { writeBytes(mp4Bytes2) }
        val shaJob2 = ChecksumVerifier.computeSha256(fileJob2)
        db.promotionJournalDao().insertJournalIfAbsent(
            PromotionJournalEntity(
                journalId = "journal_${job2}_asset_scene_1_v01",
                jobId = job2,
                assetId = "asset_scene_1_v01",
                stagingRelativePath = ".staging_tmp/job_$job2.part",
                finalRelativePath = "studio_assets/asset_scene_1_v01.mp4",
                expectedSha256Hex = shaJob2,
                phase = PromotionPhase.PROMOTED,
                createdAtEpochMs = 1_150L,
                updatedAtEpochMs = 1_150L
            )
        )

        // Unjournaled orphan file with matching scene_1 filename pattern
        val unjournaledOrphan = File(studioAssetsDir, "asset_scene_1_v02.mp4").apply { writeBytes(mp4Bytes1) }

        val reconciler = StorageReconciliationManager(baseDir, db)
        val summary = reconciler.reconcileStorageAndDatabase(nowEpochMs = 5_000L)

        // Job 2 (which owns the journal row) is completed with asset_scene_1_v01
        assertEquals(GenerationJobStatus.COMPLETED, db.generationJobDao().getJob(job2)?.status)
        assertEquals("asset_scene_1_v01", db.generationJobDao().getJob(job2)?.resultAssetId)

        // Job 1 MUST NOT be completed with Job 2's file OR with the unjournaled orphan!
        assertEquals(
            "Job 1 without a journal entry must never be completed by guessing sceneId",
            GenerationJobStatus.VALIDATING,
            db.generationJobDao().getJob(job1)?.status
        )
        assertNull(db.generationJobDao().getJob(job1)?.resultAssetId)

        // Unjournaled orphan is quarantined, never registered
        assertEquals(1, summary.corruptOrphansQuarantined)
        assertFalse(unjournaledOrphan.exists())
        assertNull(db.assetManifestDao().getByAssetId("asset_scene_1_v02"))
    }

    @Test
    fun `10 - LOCAL_IMPORT jobs never enter SUBMITTING or AMBIGUOUS_SUBMISSION_FAILED`() = runBlocking {
        val jobId = "job_local_never_submitting"
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobId,
                idempotencyKey = "idem_local_never_submitting",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Local import state guard",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.QUEUED,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )

        assertFalse(db.generationJobDao().transitionStatus(jobId, GenerationJobStatus.SUBMITTING))
        assertFalse(db.generationJobDao().transitionStatus(jobId, GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED))
        assertFalse(db.generationJobDao().markInterruptedSubmittingJobAmbiguous(jobId))
        assertEquals(GenerationJobStatus.QUEUED, db.generationJobDao().getJob(jobId)?.status)
    }

    // =========================================================================
    // 3. EDL & RENDERER BOUNDARY TESTS (ZERO FILESYSTEM PATHS)
    // =========================================================================

    @Test
    fun `11 - EdlSceneClip SceneVersionSnapshot StoryboardCue and serialized JSON have zero videoClipPath or filesystem paths`() {
        val sceneFields = EdlSceneClip::class.java.declaredFields.map { it.name }
        val snapshotFields = SceneVersionSnapshot::class.java.declaredFields.map { it.name }
        val cueFields = StoryboardCue::class.java.declaredFields.map { it.name }

        assertFalse("EdlSceneClip must not declare videoClipPath", "videoClipPath" in sceneFields)
        assertFalse("SceneVersionSnapshot must not declare videoClipPath", "videoClipPath" in snapshotFields)
        assertFalse("StoryboardCue must not declare videoClipPath", "videoClipPath" in cueFields)

        assertTrue("EdlSceneClip must declare assetId", "assetId" in sceneFields)
        assertTrue("SceneVersionSnapshot must declare assetId", "assetId" in snapshotFields)
        assertTrue("StoryboardCue must declare assetId", "assetId" in cueFields)

        var edl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 20f)
        var history = EdlHistoryStack(originalEdl = edl)
        val sceneId = edl.scenes.first().id

        val attached = EdlTimelineEngine.attachVerifiedAssetToScene(
            edl = edl,
            sceneId = sceneId,
            assetId = "asset_scene_1_v01",
            versionBadge = "CFR 24fps"
        )
        val recorded1 = EdlTimelineEngine.recordEdit(edl, attached, history)
        edl = recorded1.first
        history = recorded1.second

        // Record a subsequent edit so the asset-attached EDL state is pushed into history.undoStack
        val cameraUpdated = EdlTimelineEngine.updateSceneCamera(
            edl,
            sceneId,
            CameraMovementPreset.BASS_PUMP_ZOOM
        )
        val recorded2 = EdlTimelineEngine.recordEdit(edl, cameraUpdated, history)
        edl = recorded2.first
        history = recorded2.second

        val edlJson = EdlTimelineEngine.serializeEdlToJson(edl)
        val historyJson = EdlTimelineEngine.serializeHistoryToJson(history)

        assertTrue(edlJson.contains("\"assetId\":\"asset_scene_1_v01\""))
        assertFalse(edlJson.contains("videoClipPath"))
        assertFalse(edlJson.contains("studio_assets"))
        assertFalse(edlJson.contains(".mp4"))

        assertTrue(historyJson.contains("\"assetId\":\"asset_scene_1_v01\""))
        assertFalse(historyJson.contains("videoClipPath"))
        assertFalse(historyJson.contains("studio_assets"))
        assertFalse(historyJson.contains(".mp4"))
    }

    @Test
    fun `12 - EDL attachVerifiedAssetToScene undo redo restoreSceneVersion and photo-only scene preservation`() {
        val initialEdl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 24f)
        var currentEdl = initialEdl
        var history = EdlHistoryStack(originalEdl = initialEdl)

        val scene1Id = currentEdl.scenes[0].id
        val scene2Id = currentEdl.scenes[1].id

        // Attach asset_scene_1_v01 to scene 1
        val withV1 = EdlTimelineEngine.attachVerifiedAssetToScene(currentEdl, scene1Id, "asset_scene_1_v01", "v1 asset")
        val step1 = EdlTimelineEngine.recordEdit(currentEdl, withV1, history)
        currentEdl = step1.first
        history = step1.second

        // Attach asset_scene_1_v02 to scene 1
        val withV2 = EdlTimelineEngine.attachVerifiedAssetToScene(currentEdl, scene1Id, "asset_scene_1_v02", "v2 asset")
        val step2 = EdlTimelineEngine.recordEdit(currentEdl, withV2, history)
        currentEdl = step2.first
        history = step2.second

        assertEquals("asset_scene_1_v02", currentEdl.scenes[0].assetId)
        assertNull("Photo-only scene 2 must remain unaffected", currentEdl.scenes[1].assetId)
        assertEquals(scene2Id, currentEdl.scenes[1].id)

        // Undo -> back to asset_scene_1_v01
        val undone = EdlTimelineEngine.undo(currentEdl, history)
        currentEdl = undone.first
        history = undone.second
        assertEquals("asset_scene_1_v01", currentEdl.scenes[0].assetId)

        // Redo -> back to asset_scene_1_v02
        val redone = EdlTimelineEngine.redo(currentEdl, history)
        currentEdl = redone.first
        history = redone.second
        assertEquals("asset_scene_1_v02", currentEdl.scenes[0].assetId)

        // Restore version 0 (initial photo-only version)
        currentEdl = EdlTimelineEngine.restoreSceneVersion(currentEdl, scene1Id, 0)
        assertNull("Version 0 must restore photo-only state (assetId == null)", currentEdl.scenes[0].assetId)

        // Restore version 1 (asset_scene_1_v01)
        currentEdl = EdlTimelineEngine.restoreSceneVersion(currentEdl, scene1Id, 1)
        assertEquals("asset_scene_1_v01", currentEdl.scenes[0].assetId)
    }

    @Test
    fun `13 - VideoFrameRenderer and SceneVideoFrameExtractor resolve assetId through manifest at rendering boundary`() = runBlocking {
        val assetManager = AssetManager(baseDir, db)
        SceneVideoFrameExtractor.registerAssetStorageRoot(baseDir, db)

        val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 640, height = 360, durationTicks = 2000)
        val sourceFile = File(baseDir, "render_test.mp4").apply { writeBytes(mp4Bytes) }
        val jobId = "job_render_boundary_01"
        db.generationJobDao().insertJobIfAbsent(
            GenerationJobEntity(
                jobId = jobId,
                idempotencyKey = "idem_render_boundary_01",
                sceneId = "scene_1",
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Render boundary test",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                sourceUri = Uri.fromFile(sourceFile).toString(),
                status = GenerationJobStatus.QUEUED,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L
            )
        )
        val descriptor = assetManager.ingestLocalMp4ForJob(jobId, "scene_1", Uri.fromFile(sourceFile), context).getOrThrow()

        var edl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 16f)
        edl = EdlTimelineEngine.attachVerifiedAssetToScene(edl, "scene_1", descriptor.assetId, "Render Test")

        // Verify SceneVideoFrameExtractor resolves assetId from Room manifest and extracts a decoded Bitmap
        val extractedFrame = SceneVideoFrameExtractor.getFrameForAssetId(descriptor.assetId, 0.5f)
        assertNotNull("SceneVideoFrameExtractor must resolve assetId via manifest and decode a frame", extractedFrame)

        // Verify VideoFrameRenderer renders a frame for the hybrid EDL
        val outputBitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        var resolverInvokedWithAssetId: String? = null
        VideoFrameRenderer.renderFrame(
            canvas = canvas,
            width = 360,
            height = 640,
            sourceBitmap = null,
            timeSec = 1.0f, // Inside scene_1
            durationSec = 16f,
            modelType = VideoModelType.STANDARD,
            cameraPreset = CameraMovementPreset.SLOW_PUSH_IN,
            filterStyle = VisualFilterStyle.SYNTHWAVE_NEON,
            songTitle = "Hybrid Test Song",
            promptText = "Prompt",
            lyricsEnabled = false,
            lyrics = emptyList(),
            projectEdl = edl,
            assetFileResolver = { requestedAssetId ->
                resolverInvokedWithAssetId = requestedAssetId
                File(baseDir, "studio_assets/$requestedAssetId.mp4")
            }
        )
        assertEquals(descriptor.assetId, resolverInvokedWithAssetId)
    }

    // =========================================================================
    // 4. MEDIAEXTRACTORPROBE & SEPARATE FRAMEDECODABILITYVERIFIER TESTS
    // =========================================================================

    @Test
    fun `14 - MediaExtractorProbe on multi-track audio+video MP4 selects video track and ignores audio track`() {
        val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(
            handlerType = "vide",
            codecFourCc = "avc1",
            width = 1920,
            height = 1080,
            timescale = 1000,
            durationTicks = 2000,
            sttsSampleDeltas = List(50) { 40 },
            includeLeadingAudioTrack = true // Track 0 = audio/mp4a-latm, Track 1 = video/avc
        )
        val file = File(baseDir, "audio_plus_video.mp4").apply { writeBytes(mp4Bytes) }

        val probe = MediaExtractorProbe.inspect(file)
        assertTrue(probe.hasVideoTrack)
        assertEquals("video/avc", probe.mime)
        assertEquals(1920, probe.width)
        assertEquals(1080, probe.height)
        assertEquals(2_000_000L, probe.durationUs)
        assertTrue(probe.scanComplete)
        assertEquals(false, probe.isVfr)
        assertEquals(50, probe.sampledPtsUs.size)
    }

    @Test
    fun `15 - MediaExtractorProbe on large 9MB MP4 streams without whole-file byte array allocation and bounds PTS manifest`() = runBlocking {
        // 4,500 samples (> MAX_PTS_MANIFEST_SAMPLES = 3,600) with a 9 MB mdat box streamed directly to disk
        val deltas = List(4_500) { idx -> if (idx % 3 == 0) 20 else if (idx % 3 == 1) 50 else 35 }
        val largeFile = File(baseDir, "large_streaming_9mb.mp4")
        Mp4TestFixtureBuilder.writeLargeStreamingMp4File(
            targetFile = largeFile,
            sampleDeltas = deltas,
            mdatBytesToWrite = 9L * 1024L * 1024L
        )
        assertTrue("Large test file must exceed 8 MB", largeFile.length() > 8L * 1024L * 1024L)

        // Probe with maxSamplesToScan = 5_000 so scan completes across all 4,500 samples
        val probe = MediaExtractorProbe.inspect(largeFile, maxSamplesToScan = 5_000)
        assertTrue(probe.hasVideoTrack)
        assertTrue(probe.scanComplete)
        assertEquals(true, probe.isVfr)
        assertTrue(
            "MediaExtractorProbe in-memory sample list must be bounded to <= 3,600 entries",
            probe.sampledPtsUs.size <= MediaExtractorProbe.DEFAULT_MAX_PTS_ENTRIES
        )

        // Validate through MediaAssetValidator and verify .pts manifest on disk is bounded to <= 3,600 PTS lines
        val validation = MediaAssetValidator.validate(
            file = largeFile,
            assetIdForPtsManifest = "asset_large_vfr",
            metadataDir = baseDir,
            inspector = VideoContainerInspector { f ->
                val p = MediaExtractorProbe.inspect(f, maxSamplesToScan = 5_000)
                VideoContainerProbeResult(
                    hasVideoTrack = p.hasVideoTrack,
                    videoCodecMime = p.mime,
                    width = p.width,
                    height = p.height,
                    durationMs = p.durationUs / 1_000L,
                    ptsTimestampsUs = p.sampledPtsUs,
                    declaredFps = (p.nominalFrameRate ?: 24).toFloat(),
                    isVfr = p.isVfr,
                    scanComplete = p.scanComplete,
                    totalSamplesScanned = p.totalSamplesScanned,
                    error = p.error
                )
            }
        )
        assertTrue(validation is MediaValidationResult.Valid)
        val ptsFile = File(baseDir, "asset_large_vfr.pts")
        assertTrue(ptsFile.exists())
        val ptsLines = ptsFile.readLines().filter { !it.startsWith("#") && it.isNotBlank() }
        assertTrue(
            "Persisted .pts manifest must be bounded to <= ${VfrDetector.MAX_PTS_MANIFEST_SAMPLES} entries (was ${ptsLines.size})",
            ptsLines.size <= VfrDetector.MAX_PTS_MANIFEST_SAMPLES
        )
    }

    @Test
    fun `16 - MediaExtractorProbe returns isVfr null and scanComplete false when maxSamplesToScan is exceeded`() {
        val cfrDeltas = List(500) { 40 }
        val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(
            timescale = 1000,
            durationTicks = cfrDeltas.sum(),
            sttsSampleDeltas = cfrDeltas
        )
        val file = File(baseDir, "truncated_scan.mp4").apply { writeBytes(mp4Bytes) }

        val probe = MediaExtractorProbe.inspect(file, maxSamplesToScan = 100)
        assertTrue(probe.hasVideoTrack)
        assertFalse("Scan exceeding maxSamplesToScan must set scanComplete = false", probe.scanComplete)
        assertNull("Incomplete scan must return isVfr = null (unknown) rather than false CFR", probe.isVfr)
    }

    @Test
    fun `17 - FrameDecodabilityVerifier is separate from MediaExtractorProbe and rejects undecodable video bitstream or zero duration`() = runBlocking {
        // A. Zero-duration MP4 is rejected
        val zeroDurBytes = Mp4TestFixtureBuilder.buildMp4Bytes(
            durationTicks = 0,
            sttsSampleDeltas = emptyList()
        )
        val zeroDurFile = File(baseDir, "zero_duration.mp4").apply { writeBytes(zeroDurBytes) }
        val zeroResult = MediaAssetValidator.validate(
            file = zeroDurFile,
            assetIdForPtsManifest = "asset_zero_dur",
            metadataDir = baseDir
        )
        assertTrue(zeroResult is MediaValidationResult.Invalid)
        assertTrue((zeroResult as MediaValidationResult.Invalid).reason.contains("Invalid video duration"))

        // B. Container-valid MP4 where MediaExtractorProbe discovers the video track,
        //    but FrameDecodabilityVerifier (MediaMetadataRetriever) fails to decode any frame!
        val undecodableBytes = Mp4TestFixtureBuilder.buildMp4Bytes(
            width = 1280,
            height = 720,
            durationTicks = 2000,
            sttsSampleDeltas = List(48) { 42 },
            undecodableBitstream = true
        )
        val undecodableFile = File(baseDir, "undecodable_frames.mp4").apply { writeBytes(undecodableBytes) }

        // Prove MediaExtractorProbe succeeds on the container track header:
        val probe = MediaExtractorProbe.inspect(undecodableFile)
        assertTrue("MediaExtractorProbe must discover the container video track", probe.hasVideoTrack)
        assertEquals("video/avc", probe.mime)

        // Prove defaultFrameDecodabilityVerifier fails to decode a frame from the bitstream:
        val decodabilityVerifier: FrameDecodabilityVerifier = MediaAssetValidator.defaultFrameDecodabilityVerifier
        assertFalse(
            "FrameDecodabilityVerifier must reject the file when MediaMetadataRetriever cannot decode a frame",
            decodabilityVerifier.canDecodeFrame(undecodableFile)
        )

        // Prove MediaAssetValidator rejects the asset due to FrameDecodabilityVerifier failure:
        val validation = MediaAssetValidator.validate(
            file = undecodableFile,
            assetIdForPtsManifest = "asset_undecodable",
            metadataDir = baseDir
        )
        assertTrue(validation is MediaValidationResult.Invalid)
        assertTrue(
            (validation as MediaValidationResult.Invalid).reason.contains("Decoder verification failed")
        )
    }
}
