package com.example

import com.example.ai.FirebaseAiStoryboardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies application strings, Firebase configuration resources, and storyboard parser
 * deterministically in the local JVM test runner without requiring external runtime Maven
 * downloads (`android-all`).
 */
class ExampleRobolectricTest {

    @Test
    fun `verify app name and firebase configuration resources`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val stringsXml = stringsFile.readText()
        assertTrue(
            "app_name should be Music Video Studio",
            stringsXml.contains("<string name=\"app_name\">Music Video Studio</string>")
        )

        val appletConfigFile = File("src/main/res/values/firebase_applet_config.xml")
        assertTrue("firebase_applet_config.xml must exist", appletConfigFile.exists())
        val appletConfigXml = appletConfigFile.readText()
        assertTrue("recaptcha_site_key should be present", appletConfigXml.contains("recaptcha_site_key"))

        val googleServicesFile = File("google-services.json")
        assertTrue("google-services.json must exist", googleServicesFile.exists())
        assertTrue(
            "project_id should match gen-lang-client-0610960695",
            googleServicesFile.readText().contains("gen-lang-client-0610960695")
        )
    }

    @Test
    fun `verify FirebaseAiStoryboardRepository deterministic storyboard JSON parsing`() {
        val repo = FirebaseAiStoryboardRepository()
        val json = """
            {
              "enhancedPrompt": "Slow push-in toward neon synthwave stage",
              "directorSummary": "4-scene beat-synced storyboard",
              "instrumentalSectionsSummary": "0:00-0:04 intro",
              "chorusHighlightTiming": "0:06-0:10 chorus drop",
              "suggestedCamera": "SLOW_PUSH_IN",
              "suggestedFilter": "SYNTHWAVE_NEON",
              "cues": [
                {
                  "timeRangeLabel": "0:00 - 0:12",
                  "startFraction": 0.0,
                  "endFraction": 1.0,
                  "sceneTitle": "Neon Horizon Stage",
                  "cameraPreset": "SLOW_PUSH_IN",
                  "cameraNote": "Smooth push-in",
                  "visualTransition": "Cross-Dissolve",
                  "sectionType": "CHORUS",
                  "vocalSectionTag": "Vocal",
                  "lightingIntensity": 0.9
                }
              ],
              "syncedLyrics": []
            }
        """.trimIndent()
        val plan = repo.parseStoryboardJson(json, durationSec = 12f, isFromRealAudio = false)
        assertEquals("4-scene beat-synced storyboard", plan.directorSummary)
        assertEquals(1, plan.cues.size)
        assertEquals("Neon Horizon Stage", plan.cues.first().sceneTitle)
    }
}
