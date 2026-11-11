package com.example

import android.net.Uri
import com.example.media.AudioPcmAnalyzer
import com.example.media.EdlHistoryStack
import com.example.media.EdlTimelineEngine
import com.example.media.SynthAudioGenerator
import com.example.media.VideoFrameRenderer
import com.example.model.AudioSource
import com.example.model.AudioSpectrumTimeline
import com.example.model.CameraMovementPreset
import com.example.model.EdlTransitionType
import com.example.model.LyricLine
import com.example.model.LyricSourceType
import com.example.model.StoryboardCue
import com.example.model.VideoAspectRatio
import com.example.model.VisualFilterStyle
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
    fun `1 - verify offline configuration removes Firebase files and INTERNET permission`() {
        val googleServicesFile = File("google-services.json")
        assertFalse("google-services.json must be removed in Phase 1", googleServicesFile.exists())

        val appletConfigFile = File("src/main/res/values/firebase_applet_config.xml")
        assertFalse("firebase_applet_config.xml must be removed in Phase 1", appletConfigFile.exists())

        val envExampleFile = File("../.env.example")
        assertFalse(".env.example must be removed in Phase 1", envExampleFile.exists())

        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
        val manifestContent = manifestFile.readText()
        assertFalse(
            "AndroidManifest.xml must not request android.permission.INTERNET",
            manifestContent.contains("android.permission.INTERNET")
        )
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
    fun `5 - lyric accuracy prevents invented lyrics on imported songs and local storyboard preserves EDL timing and trimmed spectrum alignment`() {
        val importedTrackEdl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 120f,
            trimStartSec = 0f,
            trimEndSec = 120f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
            storyboardPlan = null,
            initialLyrics = emptyList()
        )
        val importedTrackState = StudioUiState(
            fullSongDurationSec = 120f,
            projectEdl = importedTrackEdl,
            storyboardPlan = null
        )
        // Must NEVER invent demo lyrics when an imported local song has no user lyrics
        assertTrue(
            "Imported local track without lyrics must have empty activeLyrics rather than fake demo sentences",
            importedTrackState.activeLyrics.isEmpty()
        )

        // Build an existing EDL on a 30s track trimmed to [10s .. 26s] (16s active duration)
        // with custom unequal scene durations (3s, 5s, 4s, 4s), a verified assetId on scene_2, and user lyrics
        var edl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 30f,
            trimStartSec = 10f,
            trimEndSec = 26f,
            defaultCamera = CameraMovementPreset.PARALLAX_DRIFT,
            initialLyrics = listOf(
                LyricLine(
                    startSec = 1.5f,
                    endSec = 4.5f,
                    text = "Electric shadows on the rain",
                    isChorusHighlight = true,
                    sourceType = LyricSourceType.USER_CORRECTED,
                    uncertaintyNote = "User-corrected timing"
                )
            )
        )
        edl = EdlTimelineEngine.changeSceneDuration(edl, "scene_1", 3f)
        edl = EdlTimelineEngine.attachVerifiedAssetToScene(edl, "scene_2", "asset_scene_2_v01")
        val preStarts = edl.scenes.map { it.startSec }
        val preEnds = edl.scenes.map { it.endSec }
        val preIds = edl.scenes.map { it.id }

        // Build 30s of 24fps PCM spectrum where source audio [13s..18s] (which maps to edited scene_2 [3s..8s])
        // has high bass & total energy (0.85f), while [0s..10s] (trimmed out) has maximum energy (1.0f)
        val fps = 24
        val frames = List(30 * fps) { frameIdx ->
            val sourceSec = frameIdx.toFloat() / fps
            when {
                sourceSec < 10f -> FloatArray(24) { 1.0f } // Trimmed out region — must be ignored!
                sourceSec in 13f..18f -> FloatArray(24) { band -> if (band <= 4) 0.90f else 0.75f } // Scene 2 peak
                else -> FloatArray(24) { 0.20f }
            }
        }
        val spectrum = AudioSpectrumTimeline(
            framesPerSecond = fps,
            durationSec = 30f,
            frames = frames,
            isFromDecodedPcm = true
        )

        val (plan, updatedEdl) = EdlTimelineEngine.buildLocalStoryboardFromEdlAndSpectrum(
            sourceDurationSec = 30f,
            trimStartSec = 10f,
            trimEndSec = 26f,
            defaultCamera = CameraMovementPreset.PARALLAX_DRIFT,
            selectedFilter = VisualFilterStyle.CYBER_CYAN,
            userPrompt = "Neon rain reflections",
            existingEdl = edl,
            spectrumTimeline = spectrum
        )

        assertEquals("Local PCM Spectrum Engine", plan.modelUsed)
        assertEquals(preIds, updatedEdl.scenes.map { it.id })
        assertEquals(preStarts, updatedEdl.scenes.map { it.startSec })
        assertEquals(preEnds, updatedEdl.scenes.map { it.endSec })
        assertEquals("asset_scene_2_v01", updatedEdl.scenes[1].assetId)
        assertEquals("INTRO", updatedEdl.scenes[0].sectionType)
        assertEquals("CHORUS_HIGHLIGHT", updatedEdl.scenes[1].sectionType)
        assertEquals(CameraMovementPreset.BASS_PUMP_ZOOM, updatedEdl.scenes[1].cameraPreset)
        assertEquals("VERSE", updatedEdl.scenes[2].sectionType)
        assertEquals("OUTRO", updatedEdl.scenes[3].sectionType)
        assertFalse("New local spectrum suggestions must not be auto-approved", updatedEdl.scenes[1].isApproved)
        assertEquals(1, updatedEdl.lyrics.size)
        assertEquals("Electric shadows on the rain", updatedEdl.lyrics.first().text)
    }

    @Test
    fun `6 - local storyboard engine preserves user settings on silent or malformed spectrum and is idempotent`() {
        var edl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 16f,
            defaultCamera = CameraMovementPreset.ORBITAL_SWAY
        )
        edl = EdlTimelineEngine.updateSceneCamera(edl, "scene_2", CameraMovementPreset.BASS_PUMP_ZOOM)
        edl = EdlTimelineEngine.updateSceneTransition(edl, "scene_2", EdlTransitionType.FLASH_CUT)

        // 1. Malformed / non-finite / digital-silence spectrum must NEVER overwrite user scene settings
        val malformedFrames = List(16 * 24) { idx ->
            if (idx % 2 == 0) FloatArray(24) { Float.NaN }
            else FloatArray(12) { 0.9f } // Wrong band count (!= 24)
        }
        val malformedSpectrum = AudioSpectrumTimeline(
            framesPerSecond = 24,
            durationSec = 16f,
            frames = malformedFrames,
            isFromDecodedPcm = true
        )

        val (_, preservedEdl) = EdlTimelineEngine.buildLocalStoryboardFromEdlAndSpectrum(
            sourceDurationSec = 16f,
            trimStartSec = 0f,
            trimEndSec = 16f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
            selectedFilter = VisualFilterStyle.SYNTHWAVE_NEON,
            userPrompt = "Keep user edits",
            existingEdl = edl,
            spectrumTimeline = malformedSpectrum
        )
        assertEquals("Must not increment revision or overwrite scenes when spectrum has no valid energy", edl, preservedEdl)
        assertEquals(CameraMovementPreset.BASS_PUMP_ZOOM, preservedEdl.scenes[1].cameraPreset)
        assertTrue(preservedEdl.scenes[1].isApproved)

        // 2. Valid spectrum: running buildLocalStoryboardFromEdlAndSpectrum twice with identical inputs must be idempotent
        val validSpectrum = AudioSpectrumTimeline(
            framesPerSecond = 24,
            durationSec = 16f,
            frames = List(16 * 24) { idx ->
                val sec = idx / 24f
                FloatArray(24) { if (sec in 8f..12f) 0.80f else 0.25f }
            },
            isFromDecodedPcm = true
        )
        val (_, firstPassEdl) = EdlTimelineEngine.buildLocalStoryboardFromEdlAndSpectrum(
            sourceDurationSec = 16f,
            trimStartSec = 0f,
            trimEndSec = 16f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
            selectedFilter = VisualFilterStyle.SYNTHWAVE_NEON,
            userPrompt = "Idempotency check",
            existingEdl = edl,
            spectrumTimeline = validSpectrum
        )
        val (_, secondPassEdl) = EdlTimelineEngine.buildLocalStoryboardFromEdlAndSpectrum(
            sourceDurationSec = 16f,
            trimStartSec = 0f,
            trimEndSec = 16f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
            selectedFilter = VisualFilterStyle.SYNTHWAVE_NEON,
            userPrompt = "Idempotency check",
            existingEdl = firstPassEdl,
            spectrumTimeline = validSpectrum
        )
        assertEquals("Second identical pass must not append duplicate SceneVersionSnapshots or increment revision", firstPassEdl, secondPassEdl)
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

