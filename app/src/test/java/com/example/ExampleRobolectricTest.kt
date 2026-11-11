package com.example

import com.example.media.AudioPcmAnalyzer
import com.example.media.EdlTimelineEngine
import com.example.media.SynthAudioGenerator
import com.example.model.CameraMovementPreset
import com.example.model.VisualFilterStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies application strings, offline resource hygiene, and deterministic local PCM
 * storyboard generation in the local JVM test runner.
 */
class ExampleRobolectricTest {

    @Test
    fun `verify app name and offline resource hygiene`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val stringsXml = stringsFile.readText()
        assertTrue(
            "app_name should be Music Video Studio",
            stringsXml.contains("<string name=\"app_name\">Music Video Studio</string>")
        )
        assertTrue(
            "ai_storyboard_button should be Build Scene Cues",
            stringsXml.contains("<string name=\"ai_storyboard_button\">Build Scene Cues</string>")
        )
        assertFalse(
            "strings.xml must not contain Firebase AI notice",
            stringsXml.contains("firebase_ai_notice")
        )

        val appletConfigFile = File("src/main/res/values/firebase_applet_config.xml")
        assertFalse("firebase_applet_config.xml must not exist", appletConfigFile.exists())

        val googleServicesFile = File("google-services.json")
        assertFalse("google-services.json must not exist", googleServicesFile.exists())
    }

    @Test
    fun `verify deterministic local PCM storyboard generation with audio cuts`() {
        val pcm = SynthAudioGenerator.generatePcmSamples(presetIndex = 0, durationSec = 12)
        val spectrum = AudioPcmAnalyzer.analyzePcmSamples(pcm, SynthAudioGenerator.SAMPLE_RATE)
        var edl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 12f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
            spectrumTimeline = spectrum
        )
        // Split audio at 3s and 6s and remove middle segment [3s..6s] -> 9s active duration
        edl = EdlTimelineEngine.splitAudioAtSourceTimestamp(edl, 3f)
        edl = EdlTimelineEngine.splitAudioAtSourceTimestamp(edl, 6f)
        val middleSegId = edl.audioSegments[1].id
        edl = EdlTimelineEngine.toggleRemoveAudioSegment(edl, middleSegId)
        assertEquals(9f, edl.totalEditedDurationSec, 0.05f)

        val (plan, generatedEdl) = EdlTimelineEngine.buildLocalStoryboardFromEdlAndSpectrum(
            sourceDurationSec = 12f,
            trimStartSec = 0f,
            trimEndSec = 12f,
            defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
            selectedFilter = VisualFilterStyle.SYNTHWAVE_NEON,
            userPrompt = "Slow push-in toward neon synthwave stage",
            existingEdl = edl,
            spectrumTimeline = spectrum
        )
        assertEquals("Local PCM Spectrum Engine", plan.modelUsed)
        assertEquals(4, generatedEdl.scenes.size)
        assertEquals("INTRO", generatedEdl.scenes.first().sectionType)
        assertEquals("OUTRO", generatedEdl.scenes.last().sectionType)
        assertEquals(4, plan.cues.size)
    }
}
