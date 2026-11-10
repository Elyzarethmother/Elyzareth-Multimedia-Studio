package com.example.model

import android.net.Uri
import com.example.R

enum class VideoModelType(val label: String, val badge: String, val description: String) {
    STANDARD("Model Standard", "FAST", "Smooth Ken Burns motion with beat-reactive audio spectrum"),
    CINEMA_PRO("Model Cinema Pro", "CINEMA", "Anamorphic lens flares, floating particles & depth parallax"),
    BEAT_PULSE("Model Beat Pulse", "CLUB", "High-energy bass zoom pulses, strobe glow & neon equalizer")
}

enum class VideoResolution(val label: String, val shortWidth: Int, val longHeight: Int, val bitrateMbps: Int) {
    HD_720P("Resolution 720P", 720, 1280, 6),
    FHD_1080P("Resolution 1080P", 1080, 1920, 12)
}

enum class VideoAspectRatio(val label: String, val widthRatio: Int, val heightRatio: Int) {
    RATIO_9_16("Aspect ratio 9 : 16", 9, 16),
    RATIO_16_9("Aspect ratio 16 : 9", 16, 9),
    RATIO_1_1("Aspect ratio 1 : 1", 1, 1);

    fun calculateDimensions(resolution: VideoResolution): Pair<Int, Int> {
        fun align16(value: Int): Int = ((value + 8) / 16) * 16
        return when (this) {
            RATIO_9_16 -> Pair(align16(resolution.shortWidth), align16(resolution.longHeight))
            RATIO_16_9 -> Pair(align16(resolution.longHeight), align16(resolution.shortWidth))
            RATIO_1_1 -> {
                val side = align16(resolution.shortWidth)
                Pair(side, side)
            }
        }
    }
}

enum class CameraMovementPreset(val label: String, val description: String) {
    SLOW_PUSH_IN("Slow Push-In", "Gradual cinematic zoom toward center with gentle drift"),
    ORBITAL_SWAY("Orbital Sway", "Floating horizontal pan with subtle pendulum tilt"),
    BASS_PUMP_ZOOM("Bass Pump Zoom", "Rhythmic zoom bursts synced to kick drum & chorus drops"),
    PARALLAX_DRIFT("Handheld Drift", "Organic documentary-style handheld breathing motion")
}

enum class VisualFilterStyle(val label: String, val primaryHex: Long, val secondaryHex: Long) {
    SYNTHWAVE_NEON("Synthwave Violet", 0xFF7B42F6, 0xFFA8F240),
    CYBER_CYAN("Cyberpunk Cyan", 0xFF00E5FF, 0xFFFF2A85),
    GOLDEN_HOUR("Golden Vinyl", 0xFFFF9E00, 0xFFFF4D79),
    MONOCHROME_NOIR("Studio Noir", 0xFFE0E0FF, 0xFF6344F5)
}

enum class LyricSourceType(val badgeLabel: String) {
    TRANSCRIBED_FROM_AUDIO("Transcribed Audio (Estimated Timing)"),
    USER_PROMPT_DERIVED("Prompt-Derived Visual Caption"),
    USER_CORRECTED("User-Corrected Lyric (Verified)"),
    SYNTH_DEMO_CAPTION("Demo Track Caption")
}

enum class EdlTransitionType(val label: String, val shortCode: String) {
    CROSS_DISSOLVE("Cross-Dissolve", "DISS"),
    FLASH_CUT("Flash Cut", "FLASH"),
    WHIP_PAN("Whip Pan", "WHIP"),
    FADE_TO_BLACK("Fade to Black", "FADE");

    companion object {
        fun fromString(raw: String): EdlTransitionType {
            val lower = raw.lowercase()
            return when {
                "flash" in lower || "strobe" in lower -> FLASH_CUT
                "whip" in lower || "pan" in lower -> WHIP_PAN
                "fade" in lower || "black" in lower -> FADE_TO_BLACK
                else -> CROSS_DISSOLVE
            }
        }
    }
}

data class LyricLine(
    val startSec: Float,
    val endSec: Float,
    val text: String,
    val isChorusHighlight: Boolean = false,
    val sourceType: LyricSourceType = LyricSourceType.USER_PROMPT_DERIVED,
    val uncertaintyNote: String = "Estimated timing window"
)

data class StoryboardCue(
    val timeRangeLabel: String,
    val startFraction: Float,
    val endFraction: Float,
    val sceneTitle: String,
    val cameraNote: String,
    val lightingIntensity: Float,
    val visualTransition: String = "Cross-Dissolve",
    val sectionType: String = "VERSE",
    val vocalSectionTag: String = "Instrumental / Unanalyzed",
    val cameraPresetOverride: CameraMovementPreset? = null,
    val assetId: String? = null
)

/**
 * Immutable snapshot of a single scene's generated or user-approved configuration.
 * Enables regenerating a single scene (or attaching an immutable verified MP4 asset by [assetId])
 * while preserving and restoring earlier versions.
 */
data class SceneVersionSnapshot(
    val versionNumber: Int,
    val label: String,
    val sceneTitle: String,
    val cameraNote: String,
    val cameraPreset: CameraMovementPreset,
    val transitionType: EdlTransitionType,
    val lightingIntensity: Float,
    val sectionType: String,
    val vocalSectionTag: String,
    val isApproved: Boolean = true,
    val assetId: String? = null
)

/**
 * Editable scene clip on the Video Scene Track of the Edit Decision List (EDL).
 * References verified media assets exclusively via provider-independent [assetId]
 * (resolved at the rendering boundary through `AssetManager` / `AssetManifestEntity`).
 */
data class EdlSceneClip(
    val id: String,
    val startSec: Float,
    val endSec: Float,
    val sceneTitle: String,
    val cameraNote: String,
    val cameraPreset: CameraMovementPreset,
    val transitionType: EdlTransitionType,
    val lightingIntensity: Float,
    val sectionType: String = "VERSE",
    val vocalSectionTag: String = "Studio Scene",
    val isApproved: Boolean = true,
    val versions: List<SceneVersionSnapshot> = emptyList(),
    val activeVersionIndex: Int = 0,
    val assetId: String? = null
) {
    val durationSec: Float
        get() = (endSec - startSec).coerceAtLeast(0.5f)
}

/**
 * Editable lyric/caption clip on the Lyrics Track of the Edit Decision List (EDL).
 */
data class EdlLyricClip(
    val id: String,
    val startSec: Float,
    val endSec: Float,
    val text: String,
    val isChorusHighlight: Boolean = false,
    val sourceType: LyricSourceType = LyricSourceType.USER_PROMPT_DERIVED,
    val uncertaintyNote: String = "Estimated timing window",
    val isUserCorrected: Boolean = false
) {
    val durationSec: Float
        get() = (endSec - startSec).coerceAtLeast(0.25f)

    fun toLyricLine(): LyricLine = LyricLine(
        startSec = startSec,
        endSec = endSec,
        text = text,
        isChorusHighlight = isChorusHighlight,
        sourceType = if (isUserCorrected) LyricSourceType.USER_CORRECTED else sourceType,
        uncertaintyNote = if (isUserCorrected) "User-corrected timing (${String.format(java.util.Locale.US, "%.1fs–%.1fs", startSec, endSec)})" else uncertaintyNote
    )
}

/**
 * Nondestructive segment on the Original Audio Track of the Edit Decision List (EDL).
 * Allows splitting at arbitrary timestamps and removing/restoring segments without altering the source MP3.
 */
data class EdlAudioSegment(
    val id: String,
    val sourceStartSec: Float,
    val sourceEndSec: Float,
    val isRemoved: Boolean = false
) {
    val sourceDurationSec: Float
        get() = (sourceEndSec - sourceStartSec).coerceAtLeast(0.1f)
}

/**
 * Master nondestructive Edit Decision List (EDL) consumed by both Live Preview and HD MP4 Export.
 */
data class ProjectEdl(
    val sourceDurationSec: Float,
    val trimStartSec: Float,
    val trimEndSec: Float,
    val scenes: List<EdlSceneClip>,
    val lyrics: List<EdlLyricClip>,
    val audioSegments: List<EdlAudioSegment>,
    val waveformPeaks: List<Float> = emptyList(),
    val revisionNumber: Int = 1
) {
    /**
     * Active (non-removed) audio segments clamped to [trimStartSec, trimEndSec].
     */
    val activeAudioSegments: List<EdlAudioSegment>
        get() = audioSegments.mapNotNull { seg ->
            if (seg.isRemoved) return@mapNotNull null
            val clampedStart = seg.sourceStartSec.coerceAtLeast(trimStartSec)
            val clampedEnd = seg.sourceEndSec.coerceAtMost(trimEndSec)
            if (clampedEnd - clampedStart >= 0.1f) {
                seg.copy(sourceStartSec = clampedStart, sourceEndSec = clampedEnd)
            } else {
                null
            }
        }

    /**
     * Effective edited duration in seconds after applying audio trims and removed segments.
     */
    val totalEditedDurationSec: Float
        get() {
            val activeSum = activeAudioSegments.sumOf { it.sourceDurationSec.toDouble() }.toFloat()
            return activeSum.coerceAtLeast(1f)
        }
}

data class AudioIntelligenceSegment(
    val timeRangeLabel: String,
    val sectionCategory: String, // e.g. INSTRUMENTAL, MALE_VOCAL, FEMALE_VOCAL, DUET, CHORUS_DROP
    val vocalTransitionNote: String,
    val confidenceNote: String
)

data class AudioIntelligenceReport(
    val summary: String,
    val detectedStructure: String,
    val vocalCharacteristics: String,
    val segments: List<AudioIntelligenceSegment>,
    val isFromRealAudioBytes: Boolean
)

data class AiStoryboardPlan(
    val enhancedPrompt: String,
    val directorSummary: String,
    val instrumentalSectionsSummary: String,
    val chorusHighlightTiming: String,
    val suggestedCamera: CameraMovementPreset,
    val suggestedFilter: VisualFilterStyle,
    val syncedLyrics: List<LyricLine>,
    val cues: List<StoryboardCue>,
    val audioIntelligence: AudioIntelligenceReport? = null,
    val modelUsed: String = "gemini-3.8-flash"
)

/**
 * Holds time-indexed 24-band spectrum frames computed from actual decoded PCM audio samples.
 * Synchronizes visualizer bars in both Preview and HD MP4 Export at [framesPerSecond] resolution.
 */
data class AudioSpectrumTimeline(
    val framesPerSecond: Int,
    val durationSec: Float,
    val frames: List<FloatArray>,
    val isFromDecodedPcm: Boolean
) {
    fun spectrumAt(timeSec: Float): FloatArray {
        if (frames.isEmpty()) return FloatArray(24) { 0.05f }
        val index = (timeSec * framesPerSecond).toInt().coerceIn(0, frames.lastIndex)
        return frames[index]
    }
}

sealed class PhotoSource {
    data class DrawableRes(val resId: Int, val title: String) : PhotoSource()
    data class LocalUri(val uri: Uri, val title: String) : PhotoSource()
}

sealed class AudioSource {
    data class LocalMp3Uri(
        val uri: Uri,
        val displayName: String,
        val durationMs: Long = 15_000L
    ) : AudioSource()

    data class MusicLink(
        val url: String,
        val parsedTitle: String,
        val synthPresetIndex: Int = 0,
        val durationMs: Long = 12_000L
    ) : AudioSource()

    data class BuiltInSynthTrack(
        val id: String,
        val title: String,
        val artist: String,
        val bpm: Int,
        val synthPresetIndex: Int,
        val durationMs: Long = 12_000L,
        val defaultPhotoRes: Int,
        val defaultPrompt: String
    ) : AudioSource()
}

data class ExportedVideoItem(
    val id: String,
    val filePath: String,
    val title: String,
    val resolutionLabel: String,
    val aspectRatioLabel: String,
    val durationSec: Int,
    val fileSizeKb: Long,
    val createdAtFormatted: String,
    val savedToGalleryUri: String? = null
)

object StudioSamplePresets {
    val presets = listOf(
        AudioSource.BuiltInSynthTrack(
            id = "preset_neon_horizon",
            title = "Neon Horizon (Suno Studio Mix)",
            artist = "Nova Pulse",
            bpm = 124,
            synthPresetIndex = 0,
            durationMs = 12_000L,
            defaultPhotoRes = R.drawable.img_sample_cyberpunk_stage_1794279281846,
            defaultPrompt = "Slow push-in toward the neon concert stage, laser beams pulsing on the chorus highlight, cyber-violet and lime atmospheric haze with kinetic synced lyrics."
        ),
        AudioSource.BuiltInSynthTrack(
            id = "preset_midnight_echo",
            title = "Midnight Vinyl Echoes",
            artist = "Kairo & The Synth",
            bpm = 110,
            synthPresetIndex = 1,
            durationMs = 12_000L,
            defaultPhotoRes = R.drawable.img_sample_neon_portrait_1794279292218,
            defaultPrompt = "Orbital camera sway around the glowing headphones portrait, deep bass zoom pulse on beat drops, warm neon light trails and floating dust particles."
        ),
        AudioSource.BuiltInSynthTrack(
            id = "preset_hyperdrive",
            title = "Solar Bass Overdrive",
            artist = "Aether Club",
            bpm = 132,
            synthPresetIndex = 2,
            durationMs = 12_000L,
            defaultPhotoRes = R.drawable.img_sample_cyberpunk_stage_1794279281846,
            defaultPrompt = "High-energy bass pump camera zoom with electric cyan equalizer rings, rapid strobe chorus highlight, and bold center-stage lyric captions."
        )
    )
}
