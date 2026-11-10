package com.example

import android.net.Uri
import com.example.ai.AudioPayload
import com.example.ai.AudioPayloadValidator
import com.example.ai.FirebaseAiStoryboardRepository
import com.example.media.AudioPcmAnalyzer
import com.example.media.EdlHistoryStack
import com.example.media.EdlTimelineEngine
import com.example.media.SynthAudioGenerator
import com.example.media.VideoFrameRenderer
import com.example.model.AudioSource
import com.example.model.CameraMovementPreset
import com.example.model.EdlTransitionType
import com.example.model.LyricLine
import com.example.model.LyricSourceType
import com.example.model.StoryboardCue
import com.example.model.VideoAspectRatio
import com.example.viewmodel.StudioUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class ExampleUnitTest {

    @Test
    fun `1 - verify Firebase configuration files exist and match provisioned project`() {
        val googleServicesFile = File("google-services.json")
        assertTrue("google-services.json must exist in app module", googleServicesFile.exists())
        val content = googleServicesFile.readText()
        assertTrue("project_id must match provisioned project", content.contains("gen-lang-client-0610960695"))
        assertTrue("package_name must match com.aistudio.musicvideo.mvxkpq", content.contains("com.aistudio.musicvideo.mvxkpq"))

        val appletConfigFile = File("src/main/res/values/firebase_applet_config.xml")
        assertTrue("firebase_applet_config.xml must exist", appletConfigFile.exists())
        assertTrue("recaptcha_site_key must be configured", appletConfigFile.readText().contains("recaptcha_site_key"))
    }

    @Test
    fun `2 - actual PCM waveform visualizer discriminates bass vs treble vs silence`() {
        val sampleRate = 44100
        val numSamples = sampleRate // 1 second

        // A. Silence -> near-floor spectrum
        val silencePcm = ShortArray(numSamples) { 0 }
        val silenceTimeline = AudioPcmAnalyzer.analyzePcmSamples(silencePcm, sampleRate)
        assertTrue(silenceTimeline.isFromDecodedPcm)
        assertTrue(silenceTimeline.frames.size in 24..25)
        val silenceFrame = silenceTimeline.spectrumAt(0.5f)
        assertTrue("Silence frame bands must remain at floor (0.04)", silenceFrame.all { it <= 0.05f })

        // B. 80 Hz Sub-Bass Sine Wave -> low bands (0..3) must dominate high bands (18..23)
        val bassPcm = ShortArray(numSamples) { i ->
            (sin(2.0 * PI * 80.0 * i / sampleRate) * 28000).toInt().toShort()
        }
        val bassTimeline = AudioPcmAnalyzer.analyzePcmSamples(bassPcm, sampleRate)
        val bassFrame = bassTimeline.spectrumAt(0.5f)
        val lowBandPeak = (0..3).maxOf { bassFrame[it] }
        val highBandAvg = (18..23).map { bassFrame[it] }.average().toFloat()
        assertTrue(
            "80Hz wave must produce higher low-band energy ($lowBandPeak) than high-band average ($highBandAvg)",
            lowBandPeak > highBandAvg * 1.5f
        )

        // C. 6000 Hz High-Treble Sine Wave -> high bands (18..23) must exceed low bands (0..2)
        val treblePcm = ShortArray(numSamples) { i ->
            (sin(2.0 * PI * 6000.0 * i / sampleRate) * 28000).toInt().toShort()
        }
        val trebleTimeline = AudioPcmAnalyzer.analyzePcmSamples(treblePcm, sampleRate)
        val trebleFrame = trebleTimeline.spectrumAt(0.5f)
        val trebleHighPeak = (18..23).maxOf { trebleFrame[it] }
        val trebleLowAvg = (0..2).map { trebleFrame[it] }.average().toFloat()
        assertTrue(
            "6000Hz wave must produce higher treble-band energy ($trebleHighPeak) than low-band average ($trebleLowAvg)",
            trebleHighPeak > trebleLowAvg * 1.5f
        )
    }

    @Test
    fun `3 - AI storyboard cue execution resolves active scene and camera overrides across timeline`() {
        val cues = listOf(
            StoryboardCue(
                timeRangeLabel = "0:00 - 0:15",
                startFraction = 0.0f,
                endFraction = 0.25f,
                sceneTitle = "Atmospheric Intro",
                cameraNote = "Slow push-in establish",
                lightingIntensity = 0.4f,
                visualTransition = "Cross-Dissolve",
                sectionType = "INTRO",
                vocalSectionTag = "Instrumental",
                cameraPresetOverride = CameraMovementPreset.SLOW_PUSH_IN
            ),
            StoryboardCue(
                timeRangeLabel = "0:15 - 0:35",
                startFraction = 0.25f,
                endFraction = 0.60f,
                sceneTitle = "Verse Sway",
                cameraNote = "Orbital camera pan",
                lightingIntensity = 0.65f,
                visualTransition = "Whip Pan",
                sectionType = "VERSE",
                vocalSectionTag = "Female Vocal",
                cameraPresetOverride = CameraMovementPreset.ORBITAL_SWAY
            ),
            StoryboardCue(
                timeRangeLabel = "0:35 - 1:00",
                startFraction = 0.60f,
                endFraction = 1.0f,
                sceneTitle = "Chorus Drop",
                cameraNote = "High energy bass pump zoom",
                lightingIntensity = 1.0f,
                visualTransition = "Flash Cut",
                sectionType = "CHORUS_HIGHLIGHT",
                vocalSectionTag = "Duet Harmony",
                cameraPresetOverride = CameraMovementPreset.BASS_PUMP_ZOOM
            )
        )

        val cueAtIntro = VideoFrameRenderer.resolveActiveCue(cues, 0.10f)
        assertNotNull(cueAtIntro)
        assertEquals("Atmospheric Intro", cueAtIntro?.sceneTitle)
        assertEquals(
            CameraMovementPreset.SLOW_PUSH_IN,
            VideoFrameRenderer.resolveSceneCameraPreset(CameraMovementPreset.PARALLAX_DRIFT, cueAtIntro)
        )

        val cueAtVerse = VideoFrameRenderer.resolveActiveCue(cues, 0.40f)
        assertNotNull(cueAtVerse)
        assertEquals("Verse Sway", cueAtVerse?.sceneTitle)
        assertEquals(
            CameraMovementPreset.ORBITAL_SWAY,
            VideoFrameRenderer.resolveSceneCameraPreset(CameraMovementPreset.SLOW_PUSH_IN, cueAtVerse)
        )

        val cueAtChorus = VideoFrameRenderer.resolveActiveCue(cues, 0.80f)
        assertNotNull(cueAtChorus)
        assertEquals("Chorus Drop", cueAtChorus?.sceneTitle)
        assertEquals(
            CameraMovementPreset.BASS_PUMP_ZOOM,
            VideoFrameRenderer.resolveSceneCameraPreset(CameraMovementPreset.SLOW_PUSH_IN, cueAtChorus)
        )
    }

    @Test
    fun `4 - full song export duration and optional user clip range calculations`() {
        // Default state exports full song (e.g. 185-second MP3 track)
        val stateFull = StudioUiState(
            fullSongDurationSec = 185f,
            exportFullSong = true,
            clipStartSec = 0f,
            clipEndSec = 185f
        )
        assertEquals(0f, stateFull.effectiveClipStartSec, 0.001f)
        assertEquals(185f, stateFull.effectiveClipEndSec, 0.001f)
        assertEquals(185, stateFull.exportDurationSec)

        // Optional user clip range (e.g. 30s to 75s chorus window)
        val stateClip = stateFull.copy(
            exportFullSong = false,
            clipStartSec = 30f,
            clipEndSec = 75f
        )
        assertEquals(30f, stateClip.effectiveClipStartSec, 0.001f)
        assertEquals(75f, stateClip.effectiveClipEndSec, 0.001f)
        assertEquals(45, stateClip.exportDurationSec)
    }

    @Test
    fun `5 - lyric accuracy prevents invented lyrics on imported songs and preserves uncertainty`() {
        val importedTrackState = StudioUiState(
            selectedAudio = AudioSource.MusicLink(
                url = "https://suno.com/song/custom-imported-track",
                parsedTitle = "Imported_Suno_Track.mp3",
                durationMs = 120_000L
            ),
            storyboardPlan = null
        )
        // Must NEVER invent demo lyrics when an imported/linked song has no AI transcription
        assertTrue(
            "Imported/linked track without AI transcription must have empty activeLyrics rather than fake demo sentences",
            importedTrackState.activeLyrics.isEmpty()
        )

        // Verify JSON parser preserves TRANSCRIBED_FROM_AUDIO vs USER_PROMPT_DERIVED and uncertaintyNote
        val repo = FirebaseAiStoryboardRepository()
        val sampleJson = """
            {
              "enhancedPrompt": "Neon stage push-in",
              "directorSummary": "4 scenes synced to vocals",
              "instrumentalSectionsSummary": "0:00-0:12 intro synth",
              "chorusHighlightTiming": "0:30-0:55 chorus bloom",
              "suggestedCamera": "ORBITAL_SWAY",
              "suggestedFilter": "CYBER_CYAN",
              "cues": [
                {
                  "timeRangeLabel": "0:00 - 0:30",
                  "startFraction": 0.0,
                  "endFraction": 0.5,
                  "sceneTitle": "Intro Build",
                  "cameraPreset": "SLOW_PUSH_IN",
                  "cameraNote": "Push in",
                  "visualTransition": "Cross-Dissolve",
                  "sectionType": "INTRO",
                  "vocalSectionTag": "Instrumental",
                  "lightingIntensity": 0.5
                }
              ],
              "syncedLyrics": [
                {
                  "startSec": 12.0,
                  "endSec": 18.5,
                  "text": "Electric shadows on the rain",
                  "isChorusHighlight": true,
                  "sourceType": "TRANSCRIBED_FROM_AUDIO",
                  "uncertaintyNote": "Estimated +/- 1.5s window from vocal phrase onset"
                }
              ]
            }
        """.trimIndent()

        val parsed = repo.parseStoryboardJson(sampleJson, durationSec = 60f, isFromRealAudio = true)
        assertEquals(1, parsed.syncedLyrics.size)
        val lyric = parsed.syncedLyrics.first()
        assertEquals(LyricSourceType.TRANSCRIBED_FROM_AUDIO, lyric.sourceType)
        assertEquals("Estimated +/- 1.5s window from vocal phrase onset", lyric.uncertaintyNote)
        assertEquals(CameraMovementPreset.SLOW_PUSH_IN, parsed.cues.first().cameraPresetOverride)
    }

    @Test
    fun `6 - audio payload safety validator rejects oversized or unsupported MIME files without truncation`() {
        assertTrue(AudioPayloadValidator.isSupportedAudioMime("audio/mpeg"))
        assertTrue(AudioPayloadValidator.isSupportedAudioMime("audio/wav"))
        assertFalse(AudioPayloadValidator.isSupportedAudioMime("video/avi"))
        assertFalse(AudioPayloadValidator.isSupportedAudioMime("application/pdf"))

        assertEquals("audio/mpeg", AudioPayloadValidator.normalizeMimeType("audio/mp3", "song.mp3"))
        assertEquals("audio/flac", AudioPayloadValidator.normalizeMimeType(null, "track.flac"))

        // Verify FirebaseAiStoryboardRepository rejects incomplete/truncated payloads immediately
        val repo = FirebaseAiStoryboardRepository()
        val truncatedPayload = AudioPayload(
            bytes = ByteArray(1024),
            mimeType = "audio/mpeg",
            totalFileSizeBytes = 25L * 1024L * 1024L,
            isCompleteTrackBytes = false,
            isRealImportedMp3 = true
        )

        var rejectedTruncated = false
        try {
            kotlinx.coroutines.runBlocking {
                repo.generateStoryboard(
                    rawPrompt = "Test",
                    songTitle = "Oversized Song",
                    durationSec = 180f,
                    lyricsEnabled = true,
                    audioPayload = truncatedPayload
                )
            }
        } catch (e: IllegalArgumentException) {
            rejectedTruncated = e.message?.contains("Refusing to truncate track") == true
        }
        assertTrue("Repository must explicitly reject truncated/oversized audio payloads", rejectedTruncated)
    }

    @Test
    fun `7 - WAV RIFF file generation produces valid PCM audio header and sample count`() {
        val durationSec = 4
        val pcm = SynthAudioGenerator.generatePcmSamples(presetIndex = 0, durationSec = durationSec)
        assertEquals(SynthAudioGenerator.SAMPLE_RATE * durationSec, pcm.size)

        val tempWav = File.createTempFile("test_synth_", ".wav")
        try {
            SynthAudioGenerator.writeWavFile(tempWav, pcm)
            assertTrue(tempWav.exists())
            assertEquals(44L + pcm.size * 2L, tempWav.length())
            val headerBytes = tempWav.readBytes().take(12).toByteArray()
            assertEquals("RIFF", String(headerBytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(headerBytes, 8, 4, Charsets.US_ASCII))
        } finally {
            tempWav.delete()
        }
    }

    @Test
    fun `8 - EDL video scene editing supports reorder delete replace duration reflow and version restore`() {
        val initialEdl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 40f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN
        )
        assertEquals(4, initialEdl.scenes.size)
        assertEquals(40f, initialEdl.totalEditedDurationSec, 0.01f)

        val firstSceneId = initialEdl.scenes[0].id
        val secondSceneId = initialEdl.scenes[1].id

        // Reorder first scene later
        val reordered = EdlTimelineEngine.reorderScene(initialEdl, firstSceneId, +1)
        assertEquals(secondSceneId, reordered.scenes[0].id)
        assertEquals(firstSceneId, reordered.scenes[1].id)
        assertEquals(0f, reordered.scenes[0].startSec, 0.01f)
        assertEquals(40f, reordered.scenes.last().endSec, 0.01f)

        // Change duration of scene and verify boundary reflow
        val resized = EdlTimelineEngine.changeSceneDuration(reordered, secondSceneId, 16f)
        assertEquals(16f, resized.scenes[0].durationSec, 0.05f)
        assertEquals(40f, resized.scenes.last().endSec, 0.05f)

        // Update camera & transition
        val camEdited = EdlTimelineEngine.updateSceneCamera(resized, firstSceneId, CameraMovementPreset.BASS_PUMP_ZOOM)
        val transEdited = EdlTimelineEngine.updateSceneTransition(camEdited, firstSceneId, EdlTransitionType.FLASH_CUT)
        val targetScene = transEdited.scenes.first { it.id == firstSceneId }
        assertEquals(CameraMovementPreset.BASS_PUMP_ZOOM, targetScene.cameraPreset)
        assertEquals(EdlTransitionType.FLASH_CUT, targetScene.transitionType)

        // Regenerate single scene and restore previous version
        val preRegenTitle = targetScene.sceneTitle
        val regenerated = EdlTimelineEngine.regenerateSingleScene(transEdited, firstSceneId)
        val regenScene = regenerated.scenes.first { it.id == firstSceneId }
        assertTrue(regenScene.versions.size >= 4)
        assertTrue(regenScene.sceneTitle != preRegenTitle)

        // Restore version index 0 (initial v1)
        val restoredV1 = EdlTimelineEngine.restoreSceneVersion(regenerated, firstSceneId, 0)
        val v1Scene = restoredV1.scenes.first { it.id == firstSceneId }
        assertEquals(preRegenTitle, v1Scene.sceneTitle)
        assertEquals(0, v1Scene.activeVersionIndex)

        // Delete scene and verify contiguous boundary reflow
        val afterDelete = EdlTimelineEngine.deleteScene(restoredV1, firstSceneId)
        assertEquals(3, afterDelete.scenes.size)
        assertEquals(0f, afterDelete.scenes.first().startSec, 0.01f)
        assertEquals(40f, afterDelete.scenes.last().endSec, 0.05f)
    }

    @Test
    fun `9 - EDL lyric editing supports transcription correction timestamp adjustment timeline nudge add and remove`() {
        val baseLyrics = listOf(
            LyricLine(
                startSec = 2f,
                endSec = 6f,
                text = "Initial rough transcription",
                isChorusHighlight = false,
                sourceType = LyricSourceType.TRANSCRIBED_FROM_AUDIO,
                uncertaintyNote = "Estimated +/- 1.5s"
            )
        )
        var edl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 30f,
            initialLyrics = baseLyrics
        )
        assertEquals(1, edl.lyrics.size)
        val lyricId = edl.lyrics.first().id

        // Correct transcription text -> marks USER_CORRECTED
        edl = EdlTimelineEngine.updateLyricLine(edl, lyricId, "Verified Neon City Lights", isChorusHighlight = true)
        val corrected = edl.lyrics.first()
        assertEquals("Verified Neon City Lights", corrected.text)
        assertTrue(corrected.isChorusHighlight)
        assertTrue(corrected.isUserCorrected)
        assertEquals(LyricSourceType.USER_CORRECTED, corrected.sourceType)

        // Adjust start/end timestamps
        edl = EdlTimelineEngine.updateLyricTimestamps(edl, lyricId, 3.5f, 8.0f)
        assertEquals(3.5f, edl.lyrics.first().startSec, 0.01f)
        assertEquals(8.0f, edl.lyrics.first().endSec, 0.01f)

        // Move/nudge caption on timeline by +1.5s
        edl = EdlTimelineEngine.moveLyricOnTimeline(edl, lyricId, 1.5f)
        assertEquals(5.0f, edl.lyrics.first().startSec, 0.01f)
        assertEquals(9.5f, edl.lyrics.first().endSec, 0.01f)

        // Add user-corrected lyric & delete original
        edl = EdlTimelineEngine.addUserCorrectedLyric(edl, atTimeSec = 12.0f, text = "Second Chorus Line", durationSec = 4.0f, isChorusHighlight = true)
        assertEquals(2, edl.lyrics.size)
        edl = EdlTimelineEngine.deleteLyricClip(edl, lyricId)
        assertEquals(1, edl.lyrics.size)
        assertEquals("Second Chorus Line", edl.lyrics.first().text)
    }

    @Test
    fun `10 - EDL nondestructive audio editing supports trim split segment removal and source timestamp mapping`() {
        var edl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 30f)
        assertEquals(30f, edl.totalEditedDurationSec, 0.01f)

        // Split at 10s and 20s -> produces 3 segments: [0..10], [10..20], [20..30]
        edl = EdlTimelineEngine.splitAudioAtSourceTimestamp(edl, 10f)
        edl = EdlTimelineEngine.splitAudioAtSourceTimestamp(edl, 20f)
        assertEquals(3, edl.audioSegments.size)

        // Remove middle segment [10..20] nondestructively
        val middleSegId = edl.audioSegments[1].id
        edl = EdlTimelineEngine.toggleRemoveAudioSegment(edl, middleSegId)
        assertTrue(edl.audioSegments[1].isRemoved)
        assertEquals(20f, edl.totalEditedDurationSec, 0.01f)

        // Verify editedTimeToSourceTime skips the removed [10..20] segment
        assertEquals(5f, EdlTimelineEngine.editedTimeToSourceTime(edl, 5f), 0.05f)
        assertEquals(22f, EdlTimelineEngine.editedTimeToSourceTime(edl, 12f), 0.05f)

        // Verify nextActiveSourceTimeIfInCut detects cut region at 14s and jumps to 20s
        assertNull(EdlTimelineEngine.nextActiveSourceTimeIfInCut(edl, 4f))
        assertEquals(20f, EdlTimelineEngine.nextActiveSourceTimeIfInCut(edl, 14f) ?: -1f, 0.05f)

        // Trim beginning to 2s and ending to 28s -> active segments become [2..10] (8s) + [20..28] (8s) = 16s
        edl = EdlTimelineEngine.trimAudioRange(edl, 2f, 28f)
        assertEquals(16f, edl.totalEditedDurationSec, 0.05f)
    }

    @Test
    fun `11 - EDL edit history supports undo redo and restore original while preserving approved scene versions`() {
        val initialEdl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 24f)
        var currentEdl = initialEdl
        var history = EdlHistoryStack(originalEdl = initialEdl)

        val sceneId = currentEdl.scenes.first().id

        // Edit 1: Regenerate first scene (creates v2)
        val regenEdl = EdlTimelineEngine.regenerateSingleScene(currentEdl, sceneId)
        val step1 = EdlTimelineEngine.recordEdit(currentEdl, regenEdl, history)
        currentEdl = step1.first
        history = step1.second
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(2, currentEdl.scenes.first().versions.size)

        // Edit 2: Trim audio to 4s..20s
        val trimmedEdl = EdlTimelineEngine.trimAudioRange(currentEdl, 4f, 20f)
        val step2 = EdlTimelineEngine.recordEdit(currentEdl, trimmedEdl, history)
        currentEdl = step2.first
        history = step2.second
        assertEquals(16f, currentEdl.totalEditedDurationSec, 0.01f)

        // Undo Edit 2 -> back to 24s duration
        val undone = EdlTimelineEngine.undo(currentEdl, history)
        currentEdl = undone.first
        history = undone.second
        assertEquals(24f, currentEdl.totalEditedDurationSec, 0.01f)
        assertTrue(history.canRedo)

        // Redo Edit 2 -> back to 16s duration
        val redone = EdlTimelineEngine.redo(currentEdl, history)
        currentEdl = redone.first
        history = redone.second
        assertEquals(16f, currentEdl.totalEditedDurationSec, 0.01f)

        // Restore Original Project -> restores 24s original state while preserving v2 in scene versions
        val restored = EdlTimelineEngine.restoreOriginalProject(currentEdl, history)
        currentEdl = restored.first
        assertEquals(24f, currentEdl.totalEditedDurationSec, 0.01f)
        assertEquals(2, currentEdl.scenes.first().versions.size)
    }

    @Test
    fun `12 - hybrid EDL scene verified assetId attachment and version restore without filesystem paths`() {
        var edl = EdlTimelineEngine.createInitialEdl(sourceDurationSec = 20f)
        val targetSceneId = edl.scenes[1].id
        assertNull(edl.scenes[1].assetId)

        edl = EdlTimelineEngine.attachVerifiedAssetToScene(
            edl = edl,
            sceneId = targetSceneId,
            assetId = "asset_scene_2_v01",
            versionBadge = "asset_scene_2_v01 (CFR 24fps)"
        )
        assertEquals("asset_scene_2_v01", edl.scenes[1].assetId)
        assertNull("Other scenes must remain photo-animated", edl.scenes[0].assetId)
        assertEquals(2, edl.scenes[1].versions.size)

        val serializedJson = EdlTimelineEngine.serializeEdlToJson(edl)
        assertTrue(serializedJson.contains("asset_scene_2_v01"))
        assertFalse("Serialized EDL must not contain videoClipPath", serializedJson.contains("videoClipPath"))
        assertFalse("Serialized EDL must not contain studio_assets filesystem paths", serializedJson.contains("studio_assets"))

        // Restore v1 (photo motion) and then restore v2 (Verified MP4 asset)
        edl = EdlTimelineEngine.restoreSceneVersion(edl, targetSceneId, 0)
        assertNull(edl.scenes[1].assetId)
        edl = EdlTimelineEngine.restoreSceneVersion(edl, targetSceneId, 1)
        assertEquals("asset_scene_2_v01", edl.scenes[1].assetId)
    }
}

