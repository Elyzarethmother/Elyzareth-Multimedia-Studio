package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import com.example.media.EdlTimelineEngine
import com.example.studio.ingestion.AssetManager
import com.example.studio.ingestion.AtomicFilePromoter
import com.example.studio.ingestion.ChecksumVerificationResult
import com.example.studio.ingestion.ChecksumVerifier
import com.example.studio.ingestion.MediaAssetValidator
import com.example.studio.ingestion.MediaValidationResult
import com.example.studio.ingestion.StorageReconciliationManager
import com.example.studio.ingestion.VfrDetector
import com.example.studio.storage.db.AssetManifestEntity
import com.example.studio.storage.db.GenerationJobEntity
import com.example.studio.storage.db.GenerationJobKind
import com.example.studio.storage.db.GenerationJobStatus
import com.example.studio.storage.db.PromotionJournalEntity
import com.example.studio.storage.db.PromotionPhase
import com.example.studio.storage.db.StudioDatabase
import com.example.studio.storage.workers.GenerationWorkOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Comprehensive Phase 2A + Phase 2B + Phase 2C Verification Suite running against real Room SQLite
 * and Robolectric [MediaExtractor] / [MediaMetadataRetriever] shadows:
 * - Database & State Machine (Tests 1–6)
 * - Validation, SHA-256 & VFR Detection via MediaExtractorProbe + FrameDecodabilityVerifier (Tests 7–14)
 * - Atomic Promotion, Promotion Journal & Crash Recovery (Tests 15–20)
 * - WorkManager Dedup/Cancellation & Local Import -> EDL Resolution (Tests 21–24)
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [TestMp4ShadowMediaExtractor::class, TestMp4ShadowMediaMetadataRetriever::class]
)
class Phase2IngestionPipelineTest {

    private lateinit var db: StudioDatabase
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = File(context.cacheDir, "test_phase2_${System.nanoTime()}.db")
        db = Room.databaseBuilder(context, StudioDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun writeTempBinaryFile(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    // =========================================================================
    // SECTION 8.1 — DATABASE AND STATE MACHINE TESTS (1–6)
    // =========================================================================

    @Test
    fun `1 - valid job creation inserts QUEUED GenerationJobEntity`() = runBlocking {
        val jobDao = db.generationJobDao()

        val job = GenerationJobEntity(
            jobId = "job_scene_1_001",
            idempotencyKey = "idem_scene_1_abc123",
            sceneId = "scene_1",
            providerId = "local_import",
            modelId = "content_resolver_mp4",
            prompt = "Test scene import",
            jobKind = GenerationJobKind.LOCAL_IMPORT,
            sourceUri = "content://media/picker/0/clip1.mp4",
            status = GenerationJobStatus.QUEUED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L
        )
        val rowId = jobDao.insertJobIfAbsent(job)
        assertTrue(rowId > 0L)

        val fetched = jobDao.getJob("job_scene_1_001")
        assertNotNull(fetched)
        assertEquals(GenerationJobStatus.QUEUED, fetched?.status)
        assertEquals("idem_scene_1_abc123", fetched?.idempotencyKey)
        assertEquals(GenerationJobKind.LOCAL_IMPORT, fetched?.jobKind)
    }

    @Test
    fun `2 - duplicate idempotency key does not create duplicate row`() = runBlocking {
        val jobDao = db.generationJobDao()

        val firstJob = GenerationJobEntity(
            jobId = "job_first",
            idempotencyKey = "idem_shared_key_999",
            sceneId = "scene_1",
            providerId = "local_import",
            modelId = "content_resolver_mp4",
            prompt = "Prompt A",
            jobKind = GenerationJobKind.LOCAL_IMPORT,
            status = GenerationJobStatus.QUEUED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L
        )
        val duplicateJob = firstJob.copy(
            jobId = "job_second_attempt",
            createdAtEpochMs = 2_000L,
            updatedAtEpochMs = 2_000L
        )

        assertTrue(jobDao.insertJobIfAbsent(firstJob) > 0L)
        assertEquals(-1L, jobDao.insertJobIfAbsent(duplicateJob))

        assertNull("Second job with duplicate idempotencyKey must not be inserted", jobDao.getJob("job_second_attempt"))
        val resolved = jobDao.getByIdempotencyKey("idem_shared_key_999")
        assertNotNull(resolved)
        assertEquals("job_first", resolved?.jobId)
    }

    @Test
    fun `3 - valid state transitions progress from QUEUED through COMPLETED`() = runBlocking {
        val jobDao = db.generationJobDao()

        val job = GenerationJobEntity(
            jobId = "job_lifecycle_1",
            idempotencyKey = "idem_lifecycle_1",
            sceneId = "scene_1",
            providerId = "future_provider",
            modelId = "future_model",
            prompt = "Full lifecycle",
            jobKind = GenerationJobKind.PROVIDER_GENERATION,
            status = GenerationJobStatus.QUEUED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L
        )
        jobDao.insertJobIfAbsent(job)

        assertTrue(jobDao.transitionStatus("job_lifecycle_1", GenerationJobStatus.SUBMITTING, submissionAttemptedAtMs = 1_100L))
        assertTrue(jobDao.transitionStatus("job_lifecycle_1", GenerationJobStatus.SUBMITTED, providerOperationId = "op_123"))
        assertTrue(jobDao.transitionStatus("job_lifecycle_1", GenerationJobStatus.PROCESSING))
        assertTrue(jobDao.transitionStatus("job_lifecycle_1", GenerationJobStatus.DOWNLOADING))
        assertTrue(jobDao.transitionStatus("job_lifecycle_1", GenerationJobStatus.VALIDATING))
        assertTrue(jobDao.transitionStatus("job_lifecycle_1", GenerationJobStatus.COMPLETED, resultAssetId = "asset_scene_1_v01"))

        val finalJob = jobDao.getJob("job_lifecycle_1")
        assertEquals(GenerationJobStatus.COMPLETED, finalJob?.status)
        assertEquals("op_123", finalJob?.providerOperationId)
        assertEquals("asset_scene_1_v01", finalJob?.resultAssetId)
    }

    @Test
    fun `4 - invalid state transitions are strictly rejected`() = runBlocking {
        val jobDao = db.generationJobDao()

        val job = GenerationJobEntity(
            jobId = "job_invalid_trans",
            idempotencyKey = "idem_invalid_trans",
            sceneId = "scene_1",
            providerId = "local_import",
            modelId = "content_resolver_mp4",
            prompt = "Invalid transition check",
            jobKind = GenerationJobKind.LOCAL_IMPORT,
            status = GenerationJobStatus.QUEUED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L
        )
        jobDao.insertJobIfAbsent(job)

        // Cannot jump directly from QUEUED to COMPLETED or VALIDATING
        assertFalse(GenerationJobStatus.isValidTransition(GenerationJobStatus.QUEUED, GenerationJobStatus.COMPLETED))
        assertFalse(jobDao.transitionStatus("job_invalid_trans", GenerationJobStatus.COMPLETED))
        assertEquals(GenerationJobStatus.QUEUED, jobDao.getJob("job_invalid_trans")?.status)

        // LOCAL_IMPORT jobs must NEVER transition to SUBMITTING or AMBIGUOUS_SUBMISSION_FAILED
        assertFalse(
            GenerationJobStatus.isValidTransition(
                GenerationJobStatus.QUEUED,
                GenerationJobStatus.SUBMITTING,
                GenerationJobKind.LOCAL_IMPORT
            )
        )
        assertFalse(jobDao.transitionStatus("job_invalid_trans", GenerationJobStatus.SUBMITTING))

        // Terminal states (COMPLETED, FAILED, AMBIGUOUS_SUBMISSION_FAILED, CANCELLED) cannot transition anywhere
        assertFalse(GenerationJobStatus.isValidTransition(GenerationJobStatus.COMPLETED, GenerationJobStatus.FAILED))
        assertFalse(GenerationJobStatus.isValidTransition(GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED, GenerationJobStatus.SUBMITTING))
        assertFalse(GenerationJobStatus.isValidTransition(GenerationJobStatus.CANCELLED, GenerationJobStatus.QUEUED))
    }

    @Test
    fun `5 - interrupted SUBMITTING job without providerOperationId becomes AMBIGUOUS_SUBMISSION_FAILED`() = runBlocking {
        val jobDao = db.generationJobDao()

        val job = GenerationJobEntity(
            jobId = "job_ambiguous_1",
            idempotencyKey = "idem_ambiguous_1",
            sceneId = "scene_2",
            providerId = "future_provider",
            modelId = "future_model",
            prompt = "Interrupted POST",
            jobKind = GenerationJobKind.PROVIDER_GENERATION,
            status = GenerationJobStatus.QUEUED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L
        )
        jobDao.insertJobIfAbsent(job)
        assertTrue(jobDao.transitionStatus("job_ambiguous_1", GenerationJobStatus.SUBMITTING, submissionAttemptedAtMs = 1_500L))

        val marked = jobDao.markInterruptedSubmittingJobAmbiguous("job_ambiguous_1", nowEpochMs = 2_000L)
        assertTrue(marked)

        val updated = jobDao.getJob("job_ambiguous_1")
        assertEquals(GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED, updated?.status)
        assertTrue(updated?.errorMessage?.contains("automatic retry blocked") == true)
    }

    @Test
    fun `6 - AMBIGUOUS_SUBMISSION_FAILED is terminal and never automatically resubmitted`() = runBlocking {
        val jobDao = db.generationJobDao()

        val job = GenerationJobEntity(
            jobId = "job_ambiguous_terminal",
            idempotencyKey = "idem_ambiguous_terminal",
            sceneId = "scene_2",
            providerId = "future_provider",
            modelId = "future_model",
            prompt = "Never auto retry",
            jobKind = GenerationJobKind.PROVIDER_GENERATION,
            status = GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED,
            submissionAttemptedAtMs = 1_500L,
            errorMessage = "Ambiguous timeout",
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L
        )
        jobDao.insertJobIfAbsent(job)

        assertTrue(GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED.isTerminal)
        for (target in GenerationJobStatus.entries) {
            assertFalse(
                "AMBIGUOUS_SUBMISSION_FAILED must reject transition to $target",
                jobDao.transitionStatus("job_ambiguous_terminal", target)
            )
        }
        assertEquals(GenerationJobStatus.AMBIGUOUS_SUBMISSION_FAILED, jobDao.getJob("job_ambiguous_terminal")?.status)
    }

    // =========================================================================
    // SECTION 8.2 — VALIDATION AND VFR TESTS (7–14)
    // =========================================================================

    @Test
    fun `7 - SHA-256 verification succeeds for valid file using 64KB streaming buffer`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_sha256_ok").toFile()
        try {
            val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(mdatPayloadSize = 150 * 1024)
            val file = writeTempBinaryFile(tempDir, "valid_clip.mp4", mp4Bytes)
            val computedHex = ChecksumVerifier.computeSha256(file)
            assertEquals(64, computedHex.length)

            val verifyResult = ChecksumVerifier.verifyFileChecksum(file, computedHex.uppercase())
            assertTrue(verifyResult is ChecksumVerificationResult.Verified)
            assertEquals(computedHex, (verifyResult as ChecksumVerificationResult.Verified).sha256Hex)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `8 - SHA-256 mismatch is rejected by ChecksumVerifier and MediaAssetValidator`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_sha256_mismatch").toFile()
        try {
            val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes()
            val file = writeTempBinaryFile(tempDir, "clip.mp4", mp4Bytes)
            val wrongSha = "0".repeat(64)

            val verifyResult = ChecksumVerifier.verifyFileChecksum(file, wrongSha)
            assertTrue(verifyResult is ChecksumVerificationResult.Mismatch)

            val validation = MediaAssetValidator.validate(
                file = file,
                assetIdForPtsManifest = "asset_test_sha_fail",
                metadataDir = tempDir,
                expectedSha256Hex = wrongSha
            )
            assertTrue(validation is MediaValidationResult.Invalid)
            assertTrue((validation as MediaValidationResult.Invalid).reason.contains("SHA-256 checksum mismatch"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `9 - corrupt non-MP4 file is rejected`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_corrupt_mp4").toFile()
        try {
            val garbageBytes = ByteArray(2048) { 0x5A.toByte() }
            val corruptFile = writeTempBinaryFile(tempDir, "corrupt.mp4", garbageBytes)

            val result = MediaAssetValidator.validate(
                file = corruptFile,
                assetIdForPtsManifest = "asset_corrupt",
                metadataDir = tempDir
            )
            assertTrue(result is MediaValidationResult.Invalid)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `10 - MP4 without video track is rejected`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_audio_only_mp4").toFile()
        try {
            val audioOnlyBytes = Mp4TestFixtureBuilder.buildMp4Bytes(handlerType = "soun", codecFourCc = "mp4a")
            val audioOnlyFile = writeTempBinaryFile(tempDir, "audio_only.mp4", audioOnlyBytes)

            val result = MediaAssetValidator.validate(
                file = audioOnlyFile,
                assetIdForPtsManifest = "asset_audio_only",
                metadataDir = tempDir
            )
            assertTrue(result is MediaValidationResult.Invalid)
            assertTrue((result as MediaValidationResult.Invalid).reason.contains("no video track"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `11 - unsupported video codec is rejected`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_unsupported_codec").toFile()
        try {
            val unsupportedCodecBytes = Mp4TestFixtureBuilder.buildMp4Bytes(handlerType = "vide", codecFourCc = "prores")
            val file = writeTempBinaryFile(tempDir, "unsupported.mp4", unsupportedCodecBytes)

            val result = MediaAssetValidator.validate(
                file = file,
                assetIdForPtsManifest = "asset_unsupported_codec",
                metadataDir = tempDir
            )
            assertTrue(result is MediaValidationResult.Invalid)
            assertTrue((result as MediaValidationResult.Invalid).reason.contains("Unsupported video codec"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `12 - CFR MP4 is marked isVfr false`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_cfr_mp4").toFile()
        try {
            val cfrDeltas = List(60) { 40 }
            val cfrBytes = Mp4TestFixtureBuilder.buildMp4Bytes(
                timescale = 1000,
                durationTicks = 2400,
                sttsSampleDeltas = cfrDeltas
            )
            val cfrFile = writeTempBinaryFile(tempDir, "cfr.mp4", cfrBytes)

            val result = MediaAssetValidator.validate(
                file = cfrFile,
                assetIdForPtsManifest = "asset_cfr_01",
                metadataDir = tempDir
            )
            assertTrue(result is MediaValidationResult.Valid)
            val valid = result as MediaValidationResult.Valid
            assertFalse("Constant 40ms frame deltas must be classified as CFR (isVfr = false)", valid.isVfr)
            assertEquals(1280, valid.width)
            assertEquals(720, valid.height)
            assertEquals(25f, valid.nominalFps, 0.5f)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `13 - VFR MP4 is marked isVfr true and produces pts manifest`() = runBlocking {
        val tempDir = Files.createTempDirectory("test_vfr_mp4").toFile()
        try {
            val vfrDeltas = List(48) { idx ->
                when (idx % 4) {
                    0 -> 20
                    1 -> 40
                    2 -> 85
                    else -> 33
                }
            }
            val vfrBytes = Mp4TestFixtureBuilder.buildMp4Bytes(
                timescale = 1000,
                durationTicks = vfrDeltas.sum(),
                sttsSampleDeltas = vfrDeltas
            )
            val vfrFile = writeTempBinaryFile(tempDir, "vfr.mp4", vfrBytes)

            val result = MediaAssetValidator.validate(
                file = vfrFile,
                assetIdForPtsManifest = "asset_vfr_01",
                metadataDir = tempDir
            )
            assertTrue("Playable VFR clip must NOT be rejected", result is MediaValidationResult.Valid)
            val valid = result as MediaValidationResult.Valid
            assertTrue("Irregular PTS deltas (>15% jitter) must be flagged isVfr = true", valid.isVfr)

            val ptsManifestFile = File(tempDir, "asset_vfr_01.pts")
            assertTrue("VFR validation must write bounded .pts manifest file", ptsManifestFile.exists())
            val ptsText = ptsManifestFile.readText()
            assertTrue(ptsText.contains("vfr=true"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `14 - duplicate or non-monotonic timestamps do not crash VFR analysis`() {
        val pathologicalPtsUs = listOf(
            0L,
            0L,
            33_333L,
            66_666L,
            50_000L,
            66_666L,
            100_000L,
            133_333L
        )
        val report = VfrDetector.analyzePresentationTimestamps(pathologicalPtsUs, fallbackFps = 30f)
        assertTrue("Nominal FPS must remain finite and > 0", report.nominalFps > 0f && report.nominalFps.isFinite())
        assertTrue("Non-monotonic or duplicate PTS must flag isVfr = true", report.isVfr)
        assertTrue("Non-monotonic/duplicate count must be > 0", report.nonMonotonicOrDuplicateCount > 0)
    }

    // =========================================================================
    // SECTION 8.3 — PROMOTION AND RECOVERY TESTS (15–20)
    // =========================================================================

    @Test
    fun `15 - atomic promotion moves part file from staging_tmp to studio_assets`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_atomic_promote").toFile()
        try {
            val stagingDir = File(baseDir, ".staging_tmp").apply { mkdirs() }
            val studioAssetsDir = File(baseDir, "studio_assets").apply { mkdirs() }
            val promoter = AtomicFilePromoter(studioAssetsDir)

            val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes()
            val stagingFile = writeTempBinaryFile(stagingDir, "job_101.part", mp4Bytes)
            val sha = ChecksumVerifier.computeSha256(stagingFile)

            val promoted = promoter.promote(stagingFile, "asset_scene_1_v01", sha)
            assertFalse("Staging .part file must no longer exist after atomic move", stagingFile.exists())
            assertTrue("Promoted .mp4 file must exist in studio_assets/", promoted.exists())
            assertEquals("asset_scene_1_v01.mp4", promoted.name)
            assertEquals(sha, ChecksumVerifier.computeSha256(promoted))
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `16 - existing immutable asset in studio_assets is never overwritten`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_immutable_no_overwrite").toFile()
        try {
            val stagingDir = File(baseDir, ".staging_tmp").apply { mkdirs() }
            val studioAssetsDir = File(baseDir, "studio_assets").apply { mkdirs() }
            val promoter = AtomicFilePromoter(studioAssetsDir)

            val originalBytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1280, height = 720)
            val staging1 = writeTempBinaryFile(stagingDir, "job_1.part", originalBytes)
            val originalSha = ChecksumVerifier.computeSha256(staging1)
            val promoted1 = promoter.promote(staging1, "asset_scene_1_v01", originalSha)

            val differentBytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 1920, height = 1080, mdatPayloadSize = 2048)
            val staging2 = writeTempBinaryFile(stagingDir, "job_2.part", differentBytes)
            val differentSha = ChecksumVerifier.computeSha256(staging2)
            assertNotEquals(originalSha, differentSha)

            var threwCollision = false
            try {
                promoter.promote(staging2, "asset_scene_1_v01", differentSha)
            } catch (e: IllegalStateException) {
                threwCollision = e.message?.contains("Immutable asset protection violation") == true
            }
            assertTrue("Promoter must refuse to overwrite an existing immutable asset with different bytes", threwCollision)
            assertEquals("Original promoted file must remain untouched", originalSha, ChecksumVerifier.computeSha256(promoted1))
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `17 - transactional manifest registration and job completion succeed together or roll back in SQLite`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_tx_commit").toFile()
        try {
            val assetManager = AssetManager(baseDir, db)
            val jobDao = db.generationJobDao()

            // A. Successful transactional commit via ingestFromInputStream
            val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes()
            val expectedSha = ChecksumVerifier.computeSha256(
                writeTempBinaryFile(baseDir, "ref.mp4", mp4Bytes)
            )
            val result = assetManager.ingestFromInputStream(
                sceneId = "scene_1",
                inputStream = ByteArrayInputStream(mp4Bytes),
                sourceIdentifier = "unit_test_stream",
                expectedSha256Hex = expectedSha,
                nowEpochMs = 5_000L
            )
            assertTrue(result.isSuccess)
            val descriptor = result.getOrThrow()
            assertEquals("asset_scene_1_v01", descriptor.assetId)
            assertNotNull(db.assetManifestDao().getByAssetId("asset_scene_1_v01"))
            val completedJob = jobDao.getJob("local_import_scene_1_5000")
            assertEquals(GenerationJobStatus.COMPLETED, completedJob?.status)
            assertEquals("asset_scene_1_v01", completedJob?.resultAssetId)
            assertEquals(PromotionPhase.COMMITTED, db.promotionJournalDao().getByJobId("local_import_scene_1_5000")?.phase)

            // B. Real SQLite transaction rollback: call commitPromotedAsset when job is in VALIDATING
            // and journal is in PREPARED (so manifest insert + job transition execute, then journalDao.markCommitted fails and rolls back!)
            val rollbackJobId = "job_tx_rollback_sqlite"
            val rollbackJournalId = "journal_${rollbackJobId}_asset_scene_2_v01"
            jobDao.insertJobIfAbsent(
                GenerationJobEntity(
                    jobId = rollbackJobId,
                    idempotencyKey = "idem_rollback_sqlite",
                    sceneId = "scene_2",
                    providerId = "local_import",
                    modelId = "local_mp4_ingest",
                    prompt = "Rollback test",
                    jobKind = GenerationJobKind.LOCAL_IMPORT,
                    status = GenerationJobStatus.VALIDATING,
                    createdAtEpochMs = 6_000L,
                    updatedAtEpochMs = 6_000L
                )
            )
            db.promotionJournalDao().insertJournalIfAbsent(
                PromotionJournalEntity(
                    journalId = rollbackJournalId,
                    jobId = rollbackJobId,
                    assetId = "asset_scene_2_v01",
                    stagingRelativePath = ".staging_tmp/job_$rollbackJobId.part",
                    finalRelativePath = "studio_assets/asset_scene_2_v01.mp4",
                    expectedSha256Hex = expectedSha,
                    phase = PromotionPhase.PREPARED, // Causes markCommitted to return false after manifest insert & job update!
                    createdAtEpochMs = 6_000L,
                    updatedAtEpochMs = 6_000L
                )
            )

            var rolledBack = false
            try {
                db.commitPromotedAsset(
                    jobId = rollbackJobId,
                    manifest = AssetManifestEntity(
                        assetId = "asset_scene_2_v01",
                        sceneId = "scene_2",
                        versionNumber = 1,
                        relativePath = "studio_assets/asset_scene_2_v01.mp4",
                        contentUri = "file:///tmp/asset_scene_2_v01.mp4",
                        sha256Hex = expectedSha,
                        byteSize = mp4Bytes.size.toLong(),
                        mimeType = "video/mp4",
                        width = 1280,
                        height = 720,
                        durationMs = 2000L,
                        nominalFps = 24f,
                        isVfr = false,
                        ptsManifestPath = null,
                        createdAtEpochMs = 6_000L
                    ),
                    journalId = rollbackJournalId,
                    nowEpochMs = 6_500L
                )
            } catch (e: IllegalStateException) {
                rolledBack = true
            }
            assertTrue("commitPromotedAsset must throw and roll back when journal commit fails", rolledBack)
            assertNull(
                "RoomDatabase.withTransaction rollback must undo the earlier insertManifestIfAbsent in the same transaction",
                db.assetManifestDao().getByAssetId("asset_scene_2_v01")
            )
            assertEquals(
                "RoomDatabase.withTransaction rollback must undo the earlier job status transition to COMPLETED",
                GenerationJobStatus.VALIDATING,
                jobDao.getJob(rollbackJobId)?.status
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `18 - journaled promoted asset in studio_assets is registered during startup recovery for exact jobId`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_orphan_recovery").toFile()
        try {
            val jobDao = db.generationJobDao()
            val journalDao = db.promotionJournalDao()

            // Simulate process termination AFTER Step 2 (file promoted to studio_assets/asset_scene_3_v01.mp4)
            // BEFORE Step 3 (Room manifest transaction committed), with journal in PROMOTED
            val jobId = "job_interrupted_after_promote"
            val job = GenerationJobEntity(
                jobId = jobId,
                idempotencyKey = "idem_interrupted_promote",
                sceneId = "scene_3",
                providerId = "local_import",
                modelId = "content_resolver_mp4",
                prompt = "Interrupted after promote",
                jobKind = GenerationJobKind.LOCAL_IMPORT,
                status = GenerationJobStatus.VALIDATING,
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_500L
            )
            jobDao.insertJobIfAbsent(job)

            val studioAssetsDir = File(baseDir, "studio_assets").apply { mkdirs() }
            val promotedFile = writeTempBinaryFile(studioAssetsDir, "asset_scene_3_v01.mp4", Mp4TestFixtureBuilder.buildMp4Bytes())
            val expectedSha = ChecksumVerifier.computeSha256(promotedFile)

            journalDao.insertJournalIfAbsent(
                PromotionJournalEntity(
                    journalId = "journal_${jobId}_asset_scene_3_v01",
                    jobId = jobId,
                    assetId = "asset_scene_3_v01",
                    stagingRelativePath = ".staging_tmp/job_$jobId.part",
                    finalRelativePath = "studio_assets/asset_scene_3_v01.mp4",
                    expectedSha256Hex = expectedSha,
                    phase = PromotionPhase.PROMOTED,
                    createdAtEpochMs = 1_400L,
                    updatedAtEpochMs = 1_450L
                )
            )

            val reconciler = StorageReconciliationManager(baseDir, db)
            val summary = reconciler.reconcileStorageAndDatabase(nowEpochMs = 10_000L)

            assertEquals(1, summary.orphanedAssetsRegistered)
            val recoveredManifest = db.assetManifestDao().getByAssetId("asset_scene_3_v01")
            assertNotNull("Journaled promoted asset in studio_assets/ must be registered in Room", recoveredManifest)
            assertEquals(expectedSha, recoveredManifest?.sha256Hex)
            assertEquals("scene_3", recoveredManifest?.sceneId)

            val recoveredJob = jobDao.getJob(jobId)
            assertEquals(GenerationJobStatus.COMPLETED, recoveredJob?.status)
            assertEquals("asset_scene_3_v01", recoveredJob?.resultAssetId)
            assertEquals(PromotionPhase.COMMITTED, journalDao.getByJobId(jobId)?.phase)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `19 - unjournaled or corrupt file in studio_assets is quarantined and never guessed onto jobs`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_corrupt_orphan").toFile()
        try {
            val studioAssetsDir = File(baseDir, "studio_assets").apply { mkdirs() }
            val corruptOrphan = writeTempBinaryFile(
                studioAssetsDir,
                "asset_scene_4_v01.mp4",
                ByteArray(1024) { 0x13.toByte() }
            )

            val reconciler = StorageReconciliationManager(baseDir, db)
            val summary = reconciler.reconcileStorageAndDatabase(nowEpochMs = 20_000L)

            assertEquals(0, summary.orphanedAssetsRegistered)
            assertEquals(1, summary.corruptOrphansQuarantined)
            assertFalse("Unjournaled/corrupt file must be moved out of studio_assets/", corruptOrphan.exists())
            assertNull("Unjournaled orphan must never be registered in asset_manifest", db.assetManifestDao().getByAssetId("asset_scene_4_v01"))
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `20 - active staging file is not deleted by recovery sweep while stale orphan part file is cleaned`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_staging_sweep").toFile()
        try {
            val jobDao = db.generationJobDao()

            jobDao.insertJobIfAbsent(
                GenerationJobEntity(
                    jobId = "active_job_1",
                    idempotencyKey = "idem_active_1",
                    sceneId = "scene_1",
                    providerId = "local_import",
                    modelId = "content_resolver_mp4",
                    prompt = "Active download",
                    jobKind = GenerationJobKind.LOCAL_IMPORT,
                    status = GenerationJobStatus.DOWNLOADING,
                    createdAtEpochMs = 1_000L,
                    updatedAtEpochMs = 1_000L
                )
            )

            val stagingDir = File(baseDir, ".staging_tmp").apply { mkdirs() }
            val activeJobPart = writeTempBinaryFile(stagingDir, "job_active_job_1.part", ByteArray(2048))
            val activeWorkerPart = writeTempBinaryFile(stagingDir, "job_worker_live_2.part", ByteArray(2048))
            val staleDeadPart = writeTempBinaryFile(stagingDir, "job_dead_old_99.part", ByteArray(2048))

            val nowMs = 100_000_000L
            val twoHoursAgoMs = nowMs - (2 * 60 * 60 * 1000L)
            activeJobPart.setLastModified(twoHoursAgoMs)
            activeWorkerPart.setLastModified(twoHoursAgoMs)
            staleDeadPart.setLastModified(twoHoursAgoMs)

            StorageReconciliationManager.markStagingJobActive("worker_live_2")
            try {
                val reconciler = StorageReconciliationManager(baseDir, db)
                val summary = reconciler.reconcileStorageAndDatabase(
                    nowEpochMs = nowMs,
                    staleStagingMaxAgeMs = 30 * 60 * 1000L
                )

                assertEquals(1, summary.staleStagingFilesDeleted)
                assertEquals(2, summary.activeStagingFilesPreserved)
                assertTrue("Staging file owned by active DB job must be preserved", activeJobPart.exists())
                assertTrue("Staging file owned by active worker must be preserved", activeWorkerPart.exists())
                assertFalse("Stale unowned .part file must be deleted", staleDeadPart.exists())
            } finally {
                StorageReconciliationManager.markStagingJobInactive("worker_live_2")
            }
        } finally {
            baseDir.deleteRecursively()
        }
    }

    // =========================================================================
    // SECTION 8.4 — WORKMANAGER AND LOCAL IMPORT TESTS (21–24)
    // =========================================================================

    @Test
    fun `21 - unique work policy prevents duplicate workers for the same jobId`() {
        val workName1 = GenerationWorkOrchestrator.uniqueWorkNameForJob("job_scene_1_abc")
        val workName2 = GenerationWorkOrchestrator.uniqueWorkNameForJob("job_scene_1_abc")
        val workNameOther = GenerationWorkOrchestrator.uniqueWorkNameForJob("job_scene_2_xyz")

        assertEquals("studio_ingest_job_job_scene_1_abc", workName1)
        assertEquals(workName1, workName2)
        assertNotEquals(workName1, workNameOther)
        assertEquals(
            "Unique work policy for per-job ingestion must be KEEP so duplicate enqueues never spawn parallel workers",
            ExistingWorkPolicy.KEEP,
            GenerationWorkOrchestrator.JOB_DEDUPLICATION_POLICY
        )
    }

    @Test
    fun `22 - cancelled work transitions non-terminal job to CANCELLED and removes staging file`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_work_cancel").toFile()
        try {
            val jobDao = db.generationJobDao()
            val assetManager = AssetManager(baseDir, db)

            jobDao.insertJobIfAbsent(
                GenerationJobEntity(
                    jobId = "job_cancel_me",
                    idempotencyKey = "idem_cancel_me",
                    sceneId = "scene_1",
                    providerId = "local_import",
                    modelId = "content_resolver_mp4",
                    prompt = "Cancel test",
                    jobKind = GenerationJobKind.LOCAL_IMPORT,
                    status = GenerationJobStatus.DOWNLOADING,
                    createdAtEpochMs = 1_000L,
                    updatedAtEpochMs = 1_000L
                )
            )
            val stagingFile = assetManager.writeStreamToStagingFile(
                "job_cancel_me",
                ByteArrayInputStream(ByteArray(4096))
            )
            assertTrue(stagingFile.exists())

            val transitioned = GenerationWorkOrchestrator.recordJobCancellationInDatabase(
                database = db,
                jobId = "job_cancel_me",
                stagingFile = stagingFile,
                nowEpochMs = 2_500L
            )
            assertTrue(transitioned)
            assertFalse("Cancelled job's staging .part file must be cleaned up", stagingFile.exists())
            assertEquals(GenerationJobStatus.CANCELLED, jobDao.getJob("job_cancel_me")?.status)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `23 - local MP4 import registers AssetManifestEntity and returns verified AssetDescriptor`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_local_import_e2e").toFile()
        try {
            val assetManager = AssetManager(baseDir, db)

            val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(
                width = 1080,
                height = 1920,
                timescale = 1000,
                durationTicks = 5000,
                sttsSampleDeltas = List(120) { 42 }
            )

            val importResult = assetManager.ingestFromInputStream(
                sceneId = "scene_1",
                inputStream = ByteArrayInputStream(mp4Bytes),
                sourceIdentifier = "content://media/picker/0/video_1080p.mp4",
                nowEpochMs = 12_345L
            )

            assertTrue("Local MP4 import must succeed", importResult.isSuccess)
            val descriptor = importResult.getOrThrow()
            assertEquals("asset_scene_1_v01", descriptor.assetId)
            assertEquals(1080, descriptor.width)
            assertEquals(1920, descriptor.height)
            assertEquals(5000L, descriptor.durationMs)
            assertFalse(descriptor.isVfr)

            val manifest = db.assetManifestDao().getByAssetId(descriptor.assetId)
            assertNotNull("AssetManifestEntity must be persisted in Room", manifest)
            assertEquals(descriptor.sha256Hex, manifest?.sha256Hex)
            assertEquals(mp4Bytes.size.toLong(), manifest?.byteSize)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `24 - EDL scene references assetId only and resolves its verified AssetDescriptor`() = runBlocking {
        val baseDir = Files.createTempDirectory("test_edl_asset_resolve").toFile()
        try {
            val assetManager = AssetManager(baseDir, db)

            val mp4Bytes = Mp4TestFixtureBuilder.buildMp4Bytes(width = 720, height = 1280, durationTicks = 4000)
            val descriptor = assetManager.ingestFromInputStream(
                sceneId = "scene_2",
                inputStream = ByteArrayInputStream(mp4Bytes),
                sourceIdentifier = "content://media/picker/0/scene2.mp4",
                nowEpochMs = 99_000L
            ).getOrThrow()

            val promotedFile = File(baseDir, "studio_assets/${descriptor.assetId}.mp4")
            assertTrue(promotedFile.exists())

            var edl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 16f)
            val sceneId = edl.scenes[1].id // "scene_2"
            edl = EdlTimelineEngine.attachVerifiedAssetToScene(
                edl = edl,
                sceneId = sceneId,
                assetId = descriptor.assetId,
                versionBadge = descriptor.assetId
            )

            val updatedScene = edl.scenes[1]
            assertEquals("asset_scene_2_v01", updatedScene.assetId)

            val resolvedDescriptor = assetManager.resolveAssetDescriptor(updatedScene.assetId!!)
            assertNotNull("EDL scene assetId must resolve to verified AssetDescriptor", resolvedDescriptor)
            assertEquals(descriptor.assetId, resolvedDescriptor?.assetId)
            assertEquals(descriptor.sha256Hex, resolvedDescriptor?.sha256Hex)
            assertEquals(720, resolvedDescriptor?.width)
            assertEquals(1280, resolvedDescriptor?.height)
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
