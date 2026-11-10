package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.model.AiStoryboardPlan
import com.example.model.AudioIntelligenceReport
import com.example.model.AudioIntelligenceSegment
import com.example.model.CameraMovementPreset
import com.example.model.LyricLine
import com.example.model.LyricSourceType
import com.example.model.StoryboardCue
import com.example.model.VisualFilterStyle
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "AiStoryboardRepo"
private const val GEMINI_MODEL_NAME = "gemini-3.8-flash"

/**
 * Payload safety limits and supported MIME types for inline audio submission to Gemini.
 */
object AudioPayloadValidator {
    const val MAX_INLINE_AUDIO_BYTES: Long = 18L * 1024L * 1024L // 18 MB safe inline threshold (<20MB API cap)

    val SUPPORTED_AUDIO_MIMES = setOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/aac",
        "audio/mp4",
        "audio/x-m4a",
        "audio/ogg",
        "audio/flac"
    )

    fun normalizeMimeType(rawMime: String?, fileName: String): String {
        val lowerMime = rawMime?.lowercase()?.trim().orEmpty()
        if (lowerMime in SUPPORTED_AUDIO_MIMES) {
            return if (lowerMime == "audio/mp3") "audio/mpeg" else lowerMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> lowerMime.ifBlank { "application/octet-stream" }
        }
    }

    fun isSupportedAudioMime(mimeType: String): Boolean {
        return mimeType.lowercase() in SUPPORTED_AUDIO_MIMES
    }
}

sealed class AudioPayloadReadResult {
    data class ValidInlinePayload(val payload: AudioPayload) : AudioPayloadReadResult()
    data class ExceedsInlineLimit(val actualSizeBytes: Long, val maxAllowedBytes: Long) : AudioPayloadReadResult()
    data class UnsupportedMimeType(val mimeType: String) : AudioPayloadReadResult()
    data class ReadError(val message: String) : AudioPayloadReadResult()
}

data class AudioPayload(
    val bytes: ByteArray,
    val mimeType: String,
    val totalFileSizeBytes: Long,
    val isCompleteTrackBytes: Boolean,
    val isRealImportedMp3: Boolean
)

/**
 * Live AI Storyboard & Multimodal Audio Intelligence Repository powered by Firebase AI Logic (`Firebase.ai`).
 * Uses `gemini-3.8-flash` with strict JSON `Schema.obj(...)` output and zero mock/placeholder responses.
 */
interface AiStoryboardRepository {
    suspend fun generateStoryboard(
        rawPrompt: String,
        songTitle: String,
        durationSec: Float,
        lyricsEnabled: Boolean,
        photoBitmap: Bitmap? = null,
        audioPayload: AudioPayload? = null
    ): AiStoryboardPlan
}

class FirebaseAiStoryboardRepository : AiStoryboardRepository {

    private val storyboardResponseSchema = Schema.obj(
        properties = mapOf(
            "enhancedPrompt" to Schema.string(
                description = "Refined cinematic music video direction expanding on the user's video prompt"
            ),
            "directorSummary" to Schema.string(
                description = "Concise summary of visual pacing, camera movements, transitions, and chorus highlight choreography"
            ),
            "instrumentalSectionsSummary" to Schema.string(
                description = "Timing and visual treatment for intro, bridge, and outro instrumental sections"
            ),
            "chorusHighlightTiming" to Schema.string(
                description = "Recommended chorus highlight timestamps and peak visual effects"
            ),
            "suggestedCamera" to Schema.enumeration(
                values = listOf("SLOW_PUSH_IN", "ORBITAL_SWAY", "BASS_PUMP_ZOOM", "PARALLAX_DRIFT"),
                description = "Primary camera movement preset matching the song and prompt"
            ),
            "suggestedFilter" to Schema.enumeration(
                values = listOf("SYNTHWAVE_NEON", "CYBER_CYAN", "GOLDEN_HOUR", "MONOCHROME_NOIR"),
                description = "Color grade and lighting filter preset"
            ),
            "cues" to Schema.array(
                description = "Sequential storyboard scene instructions covering the full video duration from 0.0 to 1.0",
                items = Schema.obj(
                    properties = mapOf(
                        "timeRangeLabel" to Schema.string(description = "Timestamp window e.g. 0:00 - 0:15"),
                        "startFraction" to Schema.double(description = "Normalized start time from 0.0 to 1.0"),
                        "endFraction" to Schema.double(description = "Normalized end time from 0.0 to 1.0"),
                        "sceneTitle" to Schema.string(description = "Short scene title"),
                        "cameraPreset" to Schema.enumeration(
                            values = listOf("SLOW_PUSH_IN", "ORBITAL_SWAY", "BASS_PUMP_ZOOM", "PARALLAX_DRIFT"),
                            description = "Specific camera movement preset to execute during this scene"
                        ),
                        "cameraNote" to Schema.string(description = "Specific camera movement and framing instruction"),
                        "visualTransition" to Schema.string(description = "Visual transition into or out of this scene (Cross-Dissolve, Flash Cut, Whip Pan, Fade to Black)"),
                        "sectionType" to Schema.string(description = "Musical section: INTRO, VERSE, CHORUS_HIGHLIGHT, INSTRUMENTAL_BREAK, or OUTRO"),
                        "vocalSectionTag" to Schema.string(description = "Vocal texture e.g. Male Vocal, Female Vocal, Duet Harmony, or Instrumental"),
                        "lightingIntensity" to Schema.double(description = "Lighting bloom intensity from 0.2 to 1.0")
                    )
                )
            ),
            "syncedLyrics" to Schema.array(
                description = "Transcribed vocal lyrics (if audible in attached audio) or user-prompt visual captions with explicit sourceType and uncertaintyNote",
                items = Schema.obj(
                    properties = mapOf(
                        "startSec" to Schema.double(description = "Estimated start time in seconds"),
                        "endSec" to Schema.double(description = "Estimated end time in seconds"),
                        "text" to Schema.string(description = "Transcribed lyric text or prompt-derived visual caption. Leave empty if instrumental and no caption requested."),
                        "isChorusHighlight" to Schema.boolean(description = "True if this line falls on the main chorus highlight"),
                        "sourceType" to Schema.enumeration(
                            values = listOf("TRANSCRIBED_FROM_AUDIO", "USER_PROMPT_DERIVED", "SYNTH_DEMO_CAPTION"),
                            description = "Whether this text was transcribed directly from audible vocals in the attached audio or derived from the user's text prompt"
                        ),
                        "uncertaintyNote" to Schema.string(description = "Honest note on transcription clarity and estimated timing uncertainty")
                    )
                )
            ),
            "audioIntelligence" to Schema.obj(
                description = "Multimodal audio structure and vocal analysis",
                properties = mapOf(
                    "summary" to Schema.string(description = "Overview of the audio track structure and energy curve"),
                    "detectedStructure" to Schema.string(description = "Detected sequence of sections (e.g. Intro -> Verse -> Chorus -> Outro)"),
                    "vocalCharacteristics" to Schema.string(description = "Analysis of vocal presence (male/female vocal register, duet sections, or instrumental-only) with honest confidence caveats"),
                    "segments" to Schema.array(
                        items = Schema.obj(
                            properties = mapOf(
                                "timeRangeLabel" to Schema.string(description = "Timestamp range"),
                                "sectionCategory" to Schema.string(description = "INSTRUMENTAL, MALE_VOCAL, FEMALE_VOCAL, DUET, or CHORUS_DROP"),
                                "vocalTransitionNote" to Schema.string(description = "Description of vocal entry, handoff, or instrumental transition"),
                                "confidenceNote" to Schema.string(description = "Acoustic basis or confidence note")
                            )
                        )
                    )
                )
            )
        )
    )

    private val model by lazy {
        val config = generationConfig {
            responseMimeType = "application/json"
            responseSchema = storyboardResponseSchema
        }
        Firebase.ai.generativeModel(
            modelName = GEMINI_MODEL_NAME,
            generationConfig = config
        )
    }

    override suspend fun generateStoryboard(
        rawPrompt: String,
        songTitle: String,
        durationSec: Float,
        lyricsEnabled: Boolean,
        photoBitmap: Bitmap?,
        audioPayload: AudioPayload?
    ): AiStoryboardPlan {
        // Validate audio payload before submitting to Gemini
        if (audioPayload != null) {
            if (!AudioPayloadValidator.isSupportedAudioMime(audioPayload.mimeType)) {
                throw IllegalArgumentException(
                    "Unsupported audio MIME type '${audioPayload.mimeType}'. Supported formats: MP3, WAV, AAC, M4A, OGG, FLAC."
                )
            }
            if (audioPayload.bytes.size > AudioPayloadValidator.MAX_INLINE_AUDIO_BYTES || !audioPayload.isCompleteTrackBytes) {
                throw IllegalArgumentException(
                    "Audio file size (${audioPayload.totalFileSizeBytes / (1024 * 1024)} MB) exceeds the 18 MB inline analysis limit. Refusing to truncate track."
                )
            }
        }

        try {
            val hasInlineAudio = audioPayload != null && audioPayload.bytes.isNotEmpty()

            val systemDirection = buildString {
                appendLine("You are an expert Music Video Director and Audio Intelligence Analyst.")
                appendLine("Track Title: \"$songTitle\"")
                appendLine("Full Track Duration: ${durationSec.toInt()} seconds")
                appendLine("Lyrics / Captions Enabled: $lyricsEnabled")
                appendLine("User Video Prompt: \"${rawPrompt.ifBlank { "Create a dynamic beat-synced music video with atmospheric lighting and chorus highlight." }}\"")
                appendLine()
                appendLine("Produce a complete structured JSON storyboard including:")
                appendLine("1. Scene descriptions, per-scene cameraPreset (SLOW_PUSH_IN, ORBITAL_SWAY, BASS_PUMP_ZOOM, PARALLAX_DRIFT), and visualTransition (Cross-Dissolve, Flash Cut, Whip Pan, Fade to Black).")
                appendLine("2. Instrumental sections and chorus highlight timing.")
                appendLine("3. Sequential storyboard cues whose startFraction and endFraction span 0.0 to 1.0 across the ${durationSec.toInt()}s duration.")
                if (audioPayload != null && hasInlineAudio) {
                    appendLine("4. LYRIC & VOCAL ACCURACY: Complete audio bytes (${audioPayload.mimeType}, ${audioPayload.bytes.size / 1024} KB) are attached.")
                    appendLine("   - If vocals are audible in the audio, transcribe the actual sung lyrics and set sourceType = TRANSCRIBED_FROM_AUDIO. State any phonetic or timing uncertainty in uncertaintyNote.")
                    appendLine("   - If the audio is purely instrumental, do NOT invent fake sung lyrics; either return an empty syncedLyrics array or, if the user's prompt explicitly includes caption text, set sourceType = USER_PROMPT_DERIVED.")
                    appendLine("   - Never claim estimated lyric timestamps are verified frame-accurate synchronization.")
                    appendLine("5. AUDIO INTELLIGENCE: Analyze the attached audio signal to identify:")
                    appendLine("   - Instrumental sections vs vocal sections.")
                    appendLine("   - Apparent male vocal register, female vocal register, or duet/layered harmony sections.")
                    appendLine("   - Vocal transitions and chorus energy drops with estimated timestamps and honest confidence caveats.")
                } else {
                    appendLine("4. LYRIC & VOCAL ACCURACY: No imported MP3 audio bytes are attached. Do NOT invent fake sung lyrics for an unattached song. Only include syncedLyrics if derived from the user's prompt (set sourceType = USER_PROMPT_DERIVED and note estimated timing in uncertaintyNote).")
                    appendLine("5. AUDIO INTELLIGENCE: State clearly in vocalCharacteristics and confidenceNote that no raw MP3 waveform was attached.")
                }
            }

            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        if (photoBitmap != null && !photoBitmap.isRecycled) {
                            image(photoBitmap)
                        }
                        if (audioPayload != null && hasInlineAudio) {
                            inlineData(audioPayload.bytes, audioPayload.mimeType)
                        }
                        text(systemDirection)
                    }
                )
            }

            val jsonText = response.text
                ?: throw IllegalStateException("Gemini returned an empty storyboard response.")

            return parseStoryboardJson(
                jsonString = jsonText,
                durationSec = durationSec,
                isFromRealAudio = audioPayload != null && hasInlineAudio && audioPayload.isRealImportedMp3
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini storyboard generation timed out after 60s", e)
            throw IllegalStateException("AI Storyboard request timed out after 60 seconds. Check your network connection and retry.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI Logic storyboard generation failed", e)
            throw e
        }
    }

    internal fun parseStoryboardJson(
        jsonString: String,
        durationSec: Float,
        isFromRealAudio: Boolean
    ): AiStoryboardPlan {
        // Attempt org.json.JSONObject first (Android runtime), falling back to regex/structural extraction
        // when running inside pure JVM unit tests where android.jar's org.json.JSONObject is an unimplemented stub.
        return try {
            val root = JSONObject(jsonString)
            // Verify JSONObject is not a JVM default-value stub returning null
            val testStr = root.optString("enhancedPrompt", "__STUB_CHECK__")
            if (testStr == null) {
                parseStoryboardJsonPureJvm(jsonString, durationSec, isFromRealAudio)
            } else {
                parseWithOrgJson(root, durationSec, isFromRealAudio)
            }
        } catch (_: Throwable) {
            parseStoryboardJsonPureJvm(jsonString, durationSec, isFromRealAudio)
        }
    }

    private fun parseWithOrgJson(
        root: JSONObject,
        durationSec: Float,
        isFromRealAudio: Boolean
    ): AiStoryboardPlan {
        val enhancedPrompt = root.optString("enhancedPrompt", "")
        val directorSummary = root.optString("directorSummary", "")
        val instrumentalSummary = root.optString("instrumentalSectionsSummary", "")
        val chorusTiming = root.optString("chorusHighlightTiming", "")

        val cameraEnumName = root.optString("suggestedCamera", "SLOW_PUSH_IN")
        val suggestedCamera = runCatching {
            CameraMovementPreset.valueOf(cameraEnumName)
        }.getOrDefault(CameraMovementPreset.SLOW_PUSH_IN)

        val filterEnumName = root.optString("suggestedFilter", "SYNTHWAVE_NEON")
        val suggestedFilter = runCatching {
            VisualFilterStyle.valueOf(filterEnumName)
        }.getOrDefault(VisualFilterStyle.SYNTHWAVE_NEON)

        val cuesArray = root.optJSONArray("cues")
        val cues = mutableListOf<StoryboardCue>()
        if (cuesArray != null) {
            for (i in 0 until cuesArray.length()) {
                val obj = cuesArray.optJSONObject(i) ?: continue
                val cueCameraOverride = obj.optString("cameraPreset", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { CameraMovementPreset.valueOf(it) }.getOrNull()
                }
                cues.add(
                    StoryboardCue(
                        timeRangeLabel = obj.optString("timeRangeLabel", "Scene ${i + 1}"),
                        startFraction = obj.optDouble("startFraction", i * 0.25).toFloat().coerceIn(0f, 1f),
                        endFraction = obj.optDouble("endFraction", (i + 1) * 0.25).toFloat().coerceIn(0f, 1f),
                        sceneTitle = obj.optString("sceneTitle", "Scene ${i + 1}"),
                        cameraNote = obj.optString("cameraNote", ""),
                        lightingIntensity = obj.optDouble("lightingIntensity", 0.7).toFloat().coerceIn(0.2f, 1f),
                        visualTransition = obj.optString("visualTransition", "Cross-Dissolve"),
                        sectionType = obj.optString("sectionType", "VERSE"),
                        vocalSectionTag = obj.optString("vocalSectionTag", "Instrumental"),
                        cameraPresetOverride = cueCameraOverride
                    )
                )
            }
        }

        val lyricsArray = root.optJSONArray("syncedLyrics")
        val syncedLyrics = mutableListOf<LyricLine>()
        if (lyricsArray != null) {
            for (i in 0 until lyricsArray.length()) {
                val obj = lyricsArray.optJSONObject(i) ?: continue
                val txt = obj.optString("text", "").trim()
                if (txt.isEmpty()) continue
                val srcTypeStr = obj.optString("sourceType", "USER_PROMPT_DERIVED")
                val srcType = runCatching {
                    LyricSourceType.valueOf(srcTypeStr)
                }.getOrDefault(LyricSourceType.USER_PROMPT_DERIVED)

                syncedLyrics.add(
                    LyricLine(
                        startSec = obj.optDouble("startSec", (i * 3).toDouble()).toFloat().coerceAtLeast(0f),
                        endSec = obj.optDouble("endSec", ((i + 1) * 3).toDouble()).toFloat().coerceAtMost(durationSec.coerceAtLeast(4f)),
                        text = txt,
                        isChorusHighlight = obj.optBoolean("isChorusHighlight", false),
                        sourceType = srcType,
                        uncertaintyNote = obj.optString("uncertaintyNote", "Estimated timing window")
                    )
                )
            }
        }

        val audioIntelObj = root.optJSONObject("audioIntelligence")
        val audioIntelligence = if (audioIntelObj != null) {
            val segsArray = audioIntelObj.optJSONArray("segments")
            val segments = mutableListOf<AudioIntelligenceSegment>()
            if (segsArray != null) {
                for (i in 0 until segsArray.length()) {
                    val sObj = segsArray.optJSONObject(i) ?: continue
                    segments.add(
                        AudioIntelligenceSegment(
                            timeRangeLabel = sObj.optString("timeRangeLabel", ""),
                            sectionCategory = sObj.optString("sectionCategory", "INSTRUMENTAL"),
                            vocalTransitionNote = sObj.optString("vocalTransitionNote", ""),
                            confidenceNote = sObj.optString("confidenceNote", "")
                        )
                    )
                }
            }
            AudioIntelligenceReport(
                summary = audioIntelObj.optString("summary", ""),
                detectedStructure = audioIntelObj.optString("detectedStructure", ""),
                vocalCharacteristics = audioIntelObj.optString("vocalCharacteristics", ""),
                segments = segments,
                isFromRealAudioBytes = isFromRealAudio
            )
        } else {
            null
        }

        return AiStoryboardPlan(
            enhancedPrompt = enhancedPrompt,
            directorSummary = directorSummary,
            instrumentalSectionsSummary = instrumentalSummary,
            chorusHighlightTiming = chorusTiming,
            suggestedCamera = suggestedCamera,
            suggestedFilter = suggestedFilter,
            syncedLyrics = syncedLyrics,
            cues = cues,
            audioIntelligence = audioIntelligence,
            modelUsed = GEMINI_MODEL_NAME
        )
    }

    private fun parseStoryboardJsonPureJvm(
        json: String,
        durationSec: Float,
        isFromRealAudio: Boolean
    ): AiStoryboardPlan {
        fun extractStr(block: String, key: String, default: String = ""): String {
            val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
            return regex.find(block)?.groupValues?.get(1) ?: default
        }
        fun extractDouble(block: String, key: String, default: Double): Double {
            val regex = Regex(""""$key"\s*:\s*([0-9.]+)""")
            return regex.find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: default
        }
        fun extractBool(block: String, key: String, default: Boolean): Boolean {
            val regex = Regex(""""$key"\s*:\s*(true|false)""")
            return regex.find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
        }

        val suggestedCamera = runCatching {
            CameraMovementPreset.valueOf(extractStr(json, "suggestedCamera", "SLOW_PUSH_IN"))
        }.getOrDefault(CameraMovementPreset.SLOW_PUSH_IN)

        val suggestedFilter = runCatching {
            VisualFilterStyle.valueOf(extractStr(json, "suggestedFilter", "SYNTHWAVE_NEON"))
        }.getOrDefault(VisualFilterStyle.SYNTHWAVE_NEON)

        val cues = mutableListOf<StoryboardCue>()
        val cuesBlockRegex = Regex(""""cues"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val cuesBody = cuesBlockRegex.find(json)?.groupValues?.get(1).orEmpty()
        val objRegex = Regex("""\{(.*?)}""", RegexOption.DOT_MATCHES_ALL)
        objRegex.findAll(cuesBody).forEachIndexed { idx, match ->
            val b = match.groupValues[1]
            val camOverride = extractStr(b, "cameraPreset", "").takeIf { it.isNotBlank() }?.let {
                runCatching { CameraMovementPreset.valueOf(it) }.getOrNull()
            }
            cues.add(
                StoryboardCue(
                    timeRangeLabel = extractStr(b, "timeRangeLabel", "Scene ${idx + 1}"),
                    startFraction = extractDouble(b, "startFraction", idx * 0.25).toFloat().coerceIn(0f, 1f),
                    endFraction = extractDouble(b, "endFraction", (idx + 1) * 0.25).toFloat().coerceIn(0f, 1f),
                    sceneTitle = extractStr(b, "sceneTitle", "Scene ${idx + 1}"),
                    cameraNote = extractStr(b, "cameraNote", ""),
                    lightingIntensity = extractDouble(b, "lightingIntensity", 0.7).toFloat().coerceIn(0.2f, 1f),
                    visualTransition = extractStr(b, "visualTransition", "Cross-Dissolve"),
                    sectionType = extractStr(b, "sectionType", "VERSE"),
                    vocalSectionTag = extractStr(b, "vocalSectionTag", "Instrumental"),
                    cameraPresetOverride = camOverride
                )
            )
        }

        val syncedLyrics = mutableListOf<LyricLine>()
        val lyricsBlockRegex = Regex(""""syncedLyrics"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val lyricsBody = lyricsBlockRegex.find(json)?.groupValues?.get(1).orEmpty()
        objRegex.findAll(lyricsBody).forEachIndexed { idx, match ->
            val b = match.groupValues[1]
            val txt = extractStr(b, "text", "").trim()
            if (txt.isNotEmpty()) {
                val srcType = runCatching {
                    LyricSourceType.valueOf(extractStr(b, "sourceType", "USER_PROMPT_DERIVED"))
                }.getOrDefault(LyricSourceType.USER_PROMPT_DERIVED)
                syncedLyrics.add(
                    LyricLine(
                        startSec = extractDouble(b, "startSec", (idx * 3).toDouble()).toFloat(),
                        endSec = extractDouble(b, "endSec", ((idx + 1) * 3).toDouble()).toFloat().coerceAtMost(durationSec),
                        text = txt,
                        isChorusHighlight = extractBool(b, "isChorusHighlight", false),
                        sourceType = srcType,
                        uncertaintyNote = extractStr(b, "uncertaintyNote", "Estimated timing window")
                    )
                )
            }
        }

        return AiStoryboardPlan(
            enhancedPrompt = extractStr(json, "enhancedPrompt"),
            directorSummary = extractStr(json, "directorSummary"),
            instrumentalSectionsSummary = extractStr(json, "instrumentalSectionsSummary"),
            chorusHighlightTiming = extractStr(json, "chorusHighlightTiming"),
            suggestedCamera = suggestedCamera,
            suggestedFilter = suggestedFilter,
            syncedLyrics = syncedLyrics,
            cues = cues,
            audioIntelligence = null,
            modelUsed = GEMINI_MODEL_NAME
        )
    }
}
