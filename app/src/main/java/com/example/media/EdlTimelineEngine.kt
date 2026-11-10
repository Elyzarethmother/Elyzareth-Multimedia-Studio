package com.example.media

import com.example.model.AiStoryboardPlan
import com.example.model.AudioSpectrumTimeline
import com.example.model.CameraMovementPreset
import com.example.model.EdlAudioSegment
import com.example.model.EdlLyricClip
import com.example.model.EdlSceneClip
import com.example.model.EdlTransitionType
import com.example.model.LyricLine
import com.example.model.LyricSourceType
import com.example.model.ProjectEdl
import com.example.model.SceneVersionSnapshot
import com.example.model.StoryboardCue
import java.util.Locale
import kotlin.math.abs

/**
 * Resolved frame state at a specific [editedTimeSec] on the approved [ProjectEdl].
 * Used identically by both the real-time Preview Canvas and the H.264/AAC HD MP4 Exporter.
 */
data class ResolvedEdlFrameState(
    val editedTimeSec: Float,
    val totalEditedDurationSec: Float,
    val sourceAudioTimeSec: Float,
    val activeScene: EdlSceneClip?,
    val sceneLocalProgress: Float,
    val activeLyric: EdlLyricClip?,
    val effectiveCamera: CameraMovementPreset,
    val effectiveTransition: EdlTransitionType
)

/**
 * Immutable undo/redo history stack for the nondestructive [ProjectEdl].
 * Preserves approved scene version snapshots across undo/redo/restore operations.
 */
data class EdlHistoryStack(
    val originalEdl: ProjectEdl,
    val undoStack: List<ProjectEdl> = emptyList(),
    val redoStack: List<ProjectEdl> = emptyList()
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}

/**
 * Deterministic, nondestructive editing engine for the Preview Editing Workstation.
 * Powers:
 * 1. Project Timeline construction (Scene track, Audio track, Lyrics track, Transitions)
 * 2. Video Scene Editing (select, delete, replace, reorder, duration reflow, camera, transition,
 *    regenerate selected scene only, restore previous scene versions)
 * 3. Lyric Editing (edit text, transcription verification, start/end timestamps, timeline nudge, add/remove)
 * 4. Audio Editing (waveform peak extraction, trim start/end, split at timestamp, remove/restore segments,
 *    nondestructive editedTime <-> sourceAudioTime mapping)
 * 5. Edit History (Undo, Redo, Restore Original Project while preserving approved scene versions)
 */
object EdlTimelineEngine {

    private const val DEFAULT_WAVEFORM_BINS = 96

    /**
     * Builds a complete initial [ProjectEdl] from the current song duration, optional [AiStoryboardPlan],
     * initial lyrics, default camera preset, and decoded [AudioSpectrumTimeline].
     */
    fun createInitialEdl(
        sourceDurationSec: Float,
        trimStartSec: Float = 0f,
        trimEndSec: Float = sourceDurationSec,
        defaultCamera: CameraMovementPreset = CameraMovementPreset.SLOW_PUSH_IN,
        storyboardPlan: AiStoryboardPlan? = null,
        initialLyrics: List<LyricLine> = emptyList(),
        spectrumTimeline: AudioSpectrumTimeline? = null
    ): ProjectEdl {
        val safeTotalDur = sourceDurationSec.coerceAtLeast(2f)
        val safeTrimStart = trimStartSec.coerceIn(0f, (safeTotalDur - 1f).coerceAtLeast(0f))
        val safeTrimEnd = trimEndSec.coerceIn(safeTrimStart + 1f, safeTotalDur)
        val activeDur = (safeTrimEnd - safeTrimStart).coerceAtLeast(1f)

        // 1. Build Video Scene Track from Storyboard Cues or 4 balanced default scenes
        val cues = storyboardPlan?.cues.orEmpty()
        val scenes = if (cues.isNotEmpty()) {
            val rawScenes = cues.mapIndexed { idx, cue ->
                val start = (cue.startFraction.coerceIn(0f, 0.95f) * activeDur)
                val end = (cue.endFraction.coerceIn(cue.startFraction + 0.05f, 1f) * activeDur)
                val cam = cue.cameraPresetOverride ?: VideoFrameRenderer.resolveSceneCameraPreset(
                    defaultCamera = storyboardPlan?.suggestedCamera ?: defaultCamera,
                    activeCue = cue
                )
                val trans = EdlTransitionType.fromString(cue.visualTransition)
                val v1 = SceneVersionSnapshot(
                    versionNumber = 1,
                    label = "v1 (Initial)",
                    sceneTitle = cue.sceneTitle,
                    cameraNote = cue.cameraNote,
                    cameraPreset = cam,
                    transitionType = trans,
                    lightingIntensity = cue.lightingIntensity.coerceIn(0.2f, 1f),
                    sectionType = cue.sectionType,
                    vocalSectionTag = cue.vocalSectionTag,
                    isApproved = true,
                    assetId = cue.assetId
                )
                EdlSceneClip(
                    id = "scene_${idx + 1}",
                    startSec = start,
                    endSec = end.coerceAtLeast(start + 1f),
                    sceneTitle = cue.sceneTitle,
                    cameraNote = cue.cameraNote,
                    cameraPreset = cam,
                    transitionType = trans,
                    lightingIntensity = cue.lightingIntensity.coerceIn(0.2f, 1f),
                    sectionType = cue.sectionType,
                    vocalSectionTag = cue.vocalSectionTag,
                    isApproved = true,
                    versions = listOf(v1),
                    activeVersionIndex = 0,
                    assetId = cue.assetId
                )
            }
            reflowSceneBoundaries(rawScenes, activeDur)
        } else {
            createDefaultFourScenes(activeDur, defaultCamera)
        }

        // 2. Build Lyrics Track
        val sourceLyrics = storyboardPlan?.syncedLyrics?.takeIf { it.isNotEmpty() } ?: initialLyrics
        val lyricClips = sourceLyrics.mapIndexed { idx, line ->
            EdlLyricClip(
                id = "lyric_${idx + 1}",
                startSec = line.startSec.coerceIn(0f, (activeDur - 0.5f).coerceAtLeast(0f)),
                endSec = line.endSec.coerceIn((line.startSec + 0.5f).coerceAtMost(activeDur), activeDur),
                text = line.text,
                isChorusHighlight = line.isChorusHighlight,
                sourceType = line.sourceType,
                uncertaintyNote = line.uncertaintyNote,
                isUserCorrected = line.sourceType == LyricSourceType.USER_CORRECTED
            )
        }

        // 3. Build Original Audio Track (single contiguous segment initially)
        val audioSegments = listOf(
            EdlAudioSegment(
                id = "audio_seg_1",
                sourceStartSec = 0f,
                sourceEndSec = safeTotalDur,
                isRemoved = false
            )
        )

        // 4. Extract actual waveform peaks from decoded PCM spectrum timeline
        val peaks = extractWaveformPeaks(spectrumTimeline, DEFAULT_WAVEFORM_BINS)

        return ProjectEdl(
            sourceDurationSec = safeTotalDur,
            trimStartSec = safeTrimStart,
            trimEndSec = safeTrimEnd,
            scenes = scenes,
            lyrics = lyricClips,
            audioSegments = audioSegments,
            waveformPeaks = peaks,
            revisionNumber = 1
        )
    }

    private fun createDefaultFourScenes(
        totalDurationSec: Float,
        defaultCamera: CameraMovementPreset
    ): List<EdlSceneClip> {
        val quarter = (totalDurationSec / 4f).coerceAtLeast(1f)
        val templates = listOf(
            Triple("Atmospheric Opening", "INTRO", CameraMovementPreset.SLOW_PUSH_IN to EdlTransitionType.CROSS_DISSOLVE),
            Triple("Rhythmic Verse Groove", "VERSE", defaultCamera to EdlTransitionType.WHIP_PAN),
            Triple("High-Energy Chorus Peak", "CHORUS", CameraMovementPreset.BASS_PUMP_ZOOM to EdlTransitionType.FLASH_CUT),
            Triple("Neon Outro Resolve", "OUTRO", CameraMovementPreset.ORBITAL_SWAY to EdlTransitionType.FADE_TO_BLACK)
        )
        return templates.mapIndexed { idx, (title, section, pair) ->
            val (cam, trans) = pair
            val start = idx * quarter
            val end = if (idx == templates.lastIndex) totalDurationSec else (idx + 1) * quarter
            val lighting = if (section == "CHORUS") 0.95f else if (section == "INTRO") 0.55f else 0.72f
            val note = "${cam.label} • ${trans.label}"
            val v1 = SceneVersionSnapshot(
                versionNumber = 1,
                label = "v1 (Default)",
                sceneTitle = title,
                cameraNote = note,
                cameraPreset = cam,
                transitionType = trans,
                lightingIntensity = lighting,
                sectionType = section,
                vocalSectionTag = if (section == "INTRO" || section == "OUTRO") "Instrumental" else "Vocal Section",
                isApproved = true
            )
            EdlSceneClip(
                id = "scene_${idx + 1}",
                startSec = start,
                endSec = end,
                sceneTitle = title,
                cameraNote = note,
                cameraPreset = cam,
                transitionType = trans,
                lightingIntensity = lighting,
                sectionType = section,
                vocalSectionTag = v1.vocalSectionTag,
                isApproved = true,
                versions = listOf(v1),
                activeVersionIndex = 0
            )
        }
    }

    /**
     * Extracts normalized waveform amplitude peaks (0.05..1.0) from the decoded [AudioSpectrumTimeline].
     */
    fun extractWaveformPeaks(
        spectrumTimeline: AudioSpectrumTimeline?,
        targetBins: Int = DEFAULT_WAVEFORM_BINS
    ): List<Float> {
        val frames = spectrumTimeline?.frames.orEmpty()
        if (frames.isEmpty() || targetBins <= 0) {
            return emptyList()
        }
        return List(targetBins) { bin ->
            val startIdx = (bin * frames.size) / targetBins
            val endIdx = (((bin + 1) * frames.size) / targetBins).coerceAtLeast(startIdx + 1).coerceAtMost(frames.size)
            var sumEnergy = 0f
            var count = 0
            for (f in startIdx until endIdx) {
                val bands = frames[f]
                if (bands.isNotEmpty()) {
                    var frameSum = 0f
                    for (b in bands) frameSum += b
                    sumEnergy += (frameSum / bands.size)
                    count++
                }
            }
            if (count > 0) (sumEnergy / count).coerceIn(0.05f, 1.0f) else 0.05f
        }
    }

    /**
     * Reflows sequential scene start/end timestamps so scenes are contiguous and span [targetTotalDurationSec].
     */
    fun reflowSceneBoundaries(
        scenes: List<EdlSceneClip>,
        targetTotalDurationSec: Float
    ): List<EdlSceneClip> {
        if (scenes.isEmpty()) return emptyList()
        val safeTotal = targetTotalDurationSec.coerceAtLeast(scenes.size * 0.5f)
        val rawSum = scenes.sumOf { it.durationSec.toDouble() }.toFloat().coerceAtLeast(0.5f)
        val scale = safeTotal / rawSum

        var cursor = 0f
        return scenes.mapIndexed { idx, scene ->
            val scaledDur = (scene.durationSec * scale).coerceAtLeast(0.5f)
            val start = cursor
            val end = if (idx == scenes.lastIndex) {
                safeTotal.coerceAtLeast(start + 0.5f)
            } else {
                (start + scaledDur).coerceAtMost(safeTotal - (scenes.size - 1 - idx) * 0.5f).coerceAtLeast(start + 0.5f)
            }
            cursor = end
            scene.copy(startSec = start, endSec = end)
        }
    }

    // =========================================================================
    // 2. VIDEO SCENE EDITING OPERATIONS
    // =========================================================================

    fun deleteScene(edl: ProjectEdl, sceneId: String): ProjectEdl {
        if (edl.scenes.size <= 1) return edl // Preserve at least 1 scene on track
        val filtered = edl.scenes.filterNot { it.id == sceneId }
        if (filtered.size == edl.scenes.size) return edl
        val reflowed = reflowSceneBoundaries(filtered, edl.totalEditedDurationSec)
        return edl.copy(scenes = reflowed, revisionNumber = edl.revisionNumber + 1)
    }

    fun replaceScene(
        edl: ProjectEdl,
        sceneId: String,
        newTitle: String,
        newSectionType: String,
        newCameraPreset: CameraMovementPreset,
        newTransition: EdlTransitionType,
        newLightingIntensity: Float,
        newCameraNote: String
    ): ProjectEdl {
        val updated = edl.scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            val nextVerNum = (scene.versions.maxOfOrNull { it.versionNumber } ?: scene.versions.size) + 1
            val snapshot = SceneVersionSnapshot(
                versionNumber = nextVerNum,
                label = "v$nextVerNum (Replaced)",
                sceneTitle = newTitle.ifBlank { scene.sceneTitle },
                cameraNote = newCameraNote.ifBlank { "${newCameraPreset.label} • ${newTransition.label}" },
                cameraPreset = newCameraPreset,
                transitionType = newTransition,
                lightingIntensity = newLightingIntensity.coerceIn(0.2f, 1.0f),
                sectionType = newSectionType.ifBlank { scene.sectionType },
                vocalSectionTag = scene.vocalSectionTag,
                isApproved = true
            )
            val newVersions = scene.versions + snapshot
            scene.copy(
                sceneTitle = snapshot.sceneTitle,
                cameraNote = snapshot.cameraNote,
                cameraPreset = snapshot.cameraPreset,
                transitionType = snapshot.transitionType,
                lightingIntensity = snapshot.lightingIntensity,
                sectionType = snapshot.sectionType,
                versions = newVersions,
                activeVersionIndex = newVersions.lastIndex
            )
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    fun addSceneAfter(edl: ProjectEdl, afterSceneId: String?): ProjectEdl {
        val insertIdx = if (afterSceneId == null) {
            edl.scenes.size
        } else {
            val found = edl.scenes.indexOfFirst { it.id == afterSceneId }
            if (found >= 0) found + 1 else edl.scenes.size
        }
        val nextNum = edl.scenes.size + 1
        val avgDur = (edl.totalEditedDurationSec / (edl.scenes.size + 1).coerceAtLeast(1)).coerceAtLeast(2f)
        val v1 = SceneVersionSnapshot(
            versionNumber = 1,
            label = "v1 (Added)",
            sceneTitle = "Custom Scene $nextNum",
            cameraNote = "Orbital Sway • Cross-Dissolve",
            cameraPreset = CameraMovementPreset.ORBITAL_SWAY,
            transitionType = EdlTransitionType.CROSS_DISSOLVE,
            lightingIntensity = 0.75f,
            sectionType = "VERSE",
            vocalSectionTag = "Custom Edit",
            isApproved = true
        )
        val newClip = EdlSceneClip(
            id = "scene_${System.nanoTime()}",
            startSec = 0f,
            endSec = avgDur,
            sceneTitle = v1.sceneTitle,
            cameraNote = v1.cameraNote,
            cameraPreset = v1.cameraPreset,
            transitionType = v1.transitionType,
            lightingIntensity = v1.lightingIntensity,
            sectionType = v1.sectionType,
            vocalSectionTag = v1.vocalSectionTag,
            isApproved = true,
            versions = listOf(v1),
            activeVersionIndex = 0
        )
        val mutable = edl.scenes.toMutableList()
        mutable.add(insertIdx.coerceIn(0, mutable.size), newClip)
        val reflowed = reflowSceneBoundaries(mutable, edl.totalEditedDurationSec)
        return edl.copy(scenes = reflowed, revisionNumber = edl.revisionNumber + 1)
    }

    fun reorderScene(edl: ProjectEdl, sceneId: String, directionDelta: Int): ProjectEdl {
        val currentIdx = edl.scenes.indexOfFirst { it.id == sceneId }
        if (currentIdx < 0) return edl
        val targetIdx = (currentIdx + directionDelta).coerceIn(0, edl.scenes.lastIndex)
        if (targetIdx == currentIdx) return edl

        val mutable = edl.scenes.toMutableList()
        val moved = mutable.removeAt(currentIdx)
        mutable.add(targetIdx, moved)

        // Recalculate contiguous startSec..endSec while keeping each scene's duration
        var cursor = 0f
        val repositioned = mutable.mapIndexed { idx, clip ->
            val dur = clip.durationSec
            val start = cursor
            val end = if (idx == mutable.lastIndex) edl.totalEditedDurationSec.coerceAtLeast(start + 0.5f) else start + dur
            cursor = end
            clip.copy(startSec = start, endSec = end)
        }
        return edl.copy(scenes = repositioned, revisionNumber = edl.revisionNumber + 1)
    }

    fun changeSceneDuration(edl: ProjectEdl, sceneId: String, newDurationSec: Float): ProjectEdl {
        val targetIdx = edl.scenes.indexOfFirst { it.id == sceneId }
        if (targetIdx < 0) return edl
        if (edl.scenes.size == 1) {
            val single = edl.scenes.first()
            return edl.copy(
                scenes = listOf(single.copy(startSec = 0f, endSec = edl.totalEditedDurationSec)),
                revisionNumber = edl.revisionNumber + 1
            )
        }

        val totalDur = edl.totalEditedDurationSec
        val maxAllowedForScene = (totalDur - (edl.scenes.size - 1) * 0.5f).coerceAtLeast(1f)
        val clampedNewDur = newDurationSec.coerceIn(0.5f, maxAllowedForScene)
        val remainingDuration = (totalDur - clampedNewDur).coerceAtLeast((edl.scenes.size - 1) * 0.5f)
        val otherScenesSum = edl.scenes
            .filterIndexed { index, _ -> index != targetIdx }
            .sumOf { it.durationSec.toDouble() }
            .toFloat()
            .coerceAtLeast(0.5f)

        var cursor = 0f
        val updated = edl.scenes.mapIndexed { idx, clip ->
            val dur = if (idx == targetIdx) {
                clampedNewDur
            } else {
                (clip.durationSec / otherScenesSum * remainingDuration).coerceAtLeast(0.5f)
            }
            val start = cursor
            val end = if (idx == edl.scenes.lastIndex) {
                totalDur.coerceAtLeast(start + 0.5f)
            } else {
                (start + dur).coerceAtMost(totalDur - (edl.scenes.size - 1 - idx) * 0.5f).coerceAtLeast(start + 0.5f)
            }
            cursor = end
            clip.copy(startSec = start, endSec = end)
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    fun updateSceneCamera(
        edl: ProjectEdl,
        sceneId: String,
        cameraPreset: CameraMovementPreset
    ): ProjectEdl {
        val updated = edl.scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            val updatedNote = "${cameraPreset.label} • ${scene.transitionType.label}"
            val nextVerNum = (scene.versions.maxOfOrNull { it.versionNumber } ?: scene.versions.size) + 1
            val snapshot = SceneVersionSnapshot(
                versionNumber = nextVerNum,
                label = "v$nextVerNum (${cameraPreset.label})",
                sceneTitle = scene.sceneTitle,
                cameraNote = updatedNote,
                cameraPreset = cameraPreset,
                transitionType = scene.transitionType,
                lightingIntensity = scene.lightingIntensity,
                sectionType = scene.sectionType,
                vocalSectionTag = scene.vocalSectionTag,
                isApproved = true
            )
            val versions = scene.versions + snapshot
            scene.copy(
                cameraPreset = cameraPreset,
                cameraNote = updatedNote,
                versions = versions,
                activeVersionIndex = versions.lastIndex
            )
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    fun updateSceneTransition(
        edl: ProjectEdl,
        sceneId: String,
        transitionType: EdlTransitionType
    ): ProjectEdl {
        val updated = edl.scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            val updatedNote = "${scene.cameraPreset.label} • ${transitionType.label}"
            val nextVerNum = (scene.versions.maxOfOrNull { it.versionNumber } ?: scene.versions.size) + 1
            val snapshot = SceneVersionSnapshot(
                versionNumber = nextVerNum,
                label = "v$nextVerNum (${transitionType.shortCode})",
                sceneTitle = scene.sceneTitle,
                cameraNote = updatedNote,
                cameraPreset = scene.cameraPreset,
                transitionType = transitionType,
                lightingIntensity = scene.lightingIntensity,
                sectionType = scene.sectionType,
                vocalSectionTag = scene.vocalSectionTag,
                isApproved = true
            )
            val versions = scene.versions + snapshot
            scene.copy(
                transitionType = transitionType,
                cameraNote = updatedNote,
                versions = versions,
                activeVersionIndex = versions.lastIndex
            )
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    /**
     * Regenerates ONLY the selected scene without modifying any other scene on the timeline.
     * Appends a new [SceneVersionSnapshot] to the scene's `versions` history so previous
     * versions remain restorable at any time.
     */
    fun regenerateSingleScene(edl: ProjectEdl, sceneId: String): ProjectEdl {
        val allCameras = CameraMovementPreset.entries
        val allTransitions = EdlTransitionType.entries
        val sectionStyles = listOf("VERSE", "CHORUS", "INSTRUMENTAL", "BRIDGE")
        val descriptors = listOf(
            "Anamorphic Pulse",
            "Kinetic Spotlight",
            "Prism Horizon",
            "Strobe Overdrive",
            "Velvet Drift",
            "Cybernetic Bloom"
        )

        val updated = edl.scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            val nextVerNum = (scene.versions.maxOfOrNull { it.versionNumber } ?: scene.versions.size) + 1
            val nextCam = allCameras[(scene.cameraPreset.ordinal + 1) % allCameras.size]
            val nextTrans = allTransitions[(scene.transitionType.ordinal + 1) % allTransitions.size]
            val nextSection = if (nextVerNum % 2 == 0) "CHORUS" else sectionStyles[nextVerNum % sectionStyles.size]
            val nextLighting = if (nextSection == "CHORUS") 0.96f else (0.58f + (nextVerNum % 4) * 0.10f).coerceIn(0.45f, 0.95f)
            val descriptor = descriptors[(nextVerNum - 1) % descriptors.size]
            val newTitle = "${scene.sceneTitle.substringBefore(" (")} ($descriptor)"
            val newNote = "${nextCam.label} • ${nextTrans.label} • Light ${(nextLighting * 100).toInt()}%"

            val snapshot = SceneVersionSnapshot(
                versionNumber = nextVerNum,
                label = "v$nextVerNum ($descriptor)",
                sceneTitle = newTitle,
                cameraNote = newNote,
                cameraPreset = nextCam,
                transitionType = nextTrans,
                lightingIntensity = nextLighting,
                sectionType = nextSection,
                vocalSectionTag = scene.vocalSectionTag,
                isApproved = true
            )
            val newVersions = scene.versions + snapshot
            scene.copy(
                sceneTitle = snapshot.sceneTitle,
                cameraNote = snapshot.cameraNote,
                cameraPreset = snapshot.cameraPreset,
                transitionType = snapshot.transitionType,
                lightingIntensity = snapshot.lightingIntensity,
                sectionType = snapshot.sectionType,
                versions = newVersions,
                activeVersionIndex = newVersions.lastIndex
            )
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    /**
     * Attaches a verified, atomically promoted MP4 asset by its immutable [assetId]
     * to ONLY the selected scene, saving a new [SceneVersionSnapshot] so earlier
     * photo-based or video-based versions remain restorable.
     */
    fun attachVerifiedAssetToScene(
        edl: ProjectEdl,
        sceneId: String,
        assetId: String,
        versionBadge: String = "Verified MP4"
    ): ProjectEdl {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        require(!assetId.contains('/') && !assetId.contains('\\')) {
            "assetId must be an immutable identifier, not a filesystem path: $assetId"
        }
        val updated = edl.scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            val nextVerNum = (scene.versions.maxOfOrNull { it.versionNumber } ?: scene.versions.size) + 1
            val cleanBaseTitle = scene.sceneTitle.substringBefore(" [")
            val newTitle = "$cleanBaseTitle [$versionBadge]"
            val newNote = "Verified Asset ($assetId) • ${scene.transitionType.label}"
            val snapshot = SceneVersionSnapshot(
                versionNumber = nextVerNum,
                label = "v$nextVerNum ($versionBadge)",
                sceneTitle = newTitle,
                cameraNote = newNote,
                cameraPreset = scene.cameraPreset,
                transitionType = scene.transitionType,
                lightingIntensity = scene.lightingIntensity,
                sectionType = scene.sectionType,
                vocalSectionTag = scene.vocalSectionTag,
                isApproved = true,
                assetId = assetId
            )
            val newVersions = scene.versions + snapshot
            scene.copy(
                sceneTitle = snapshot.sceneTitle,
                cameraNote = snapshot.cameraNote,
                versions = newVersions,
                activeVersionIndex = newVersions.lastIndex,
                assetId = assetId
            )
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    /**
     * Restores a previously generated or approved [SceneVersionSnapshot] for [sceneId],
     * including whether that version uses photo animation (`assetId == null`) or a verified MP4 asset.
     */
    fun restoreSceneVersion(edl: ProjectEdl, sceneId: String, versionIndex: Int): ProjectEdl {
        val updated = edl.scenes.map { scene ->
            if (scene.id != sceneId) return@map scene
            if (versionIndex !in scene.versions.indices) return@map scene
            val target = scene.versions[versionIndex]
            scene.copy(
                sceneTitle = target.sceneTitle,
                cameraNote = target.cameraNote,
                cameraPreset = target.cameraPreset,
                transitionType = target.transitionType,
                lightingIntensity = target.lightingIntensity,
                sectionType = target.sectionType,
                vocalSectionTag = target.vocalSectionTag,
                isApproved = target.isApproved,
                activeVersionIndex = versionIndex,
                assetId = target.assetId
            )
        }
        return edl.copy(scenes = updated, revisionNumber = edl.revisionNumber + 1)
    }

    // =========================================================================
    // 3. LYRIC EDITING OPERATIONS
    // =========================================================================

    fun updateLyricLine(
        edl: ProjectEdl,
        lyricId: String,
        newText: String,
        isChorusHighlight: Boolean
    ): ProjectEdl {
        val updated = edl.lyrics.map { clip ->
            if (clip.id != lyricId) return@map clip
            clip.copy(
                text = newText,
                isChorusHighlight = isChorusHighlight,
                sourceType = LyricSourceType.USER_CORRECTED,
                uncertaintyNote = "User-corrected transcription",
                isUserCorrected = true
            )
        }
        return edl.copy(lyrics = updated, revisionNumber = edl.revisionNumber + 1)
    }

    fun updateLyricTimestamps(
        edl: ProjectEdl,
        lyricId: String,
        newStartSec: Float,
        newEndSec: Float
    ): ProjectEdl {
        val maxDur = edl.totalEditedDurationSec
        val safeStart = newStartSec.coerceIn(0f, (maxDur - 0.25f).coerceAtLeast(0f))
        val safeEnd = newEndSec.coerceIn(safeStart + 0.25f, maxDur.coerceAtLeast(safeStart + 0.25f))
        val updated = edl.lyrics.map { clip ->
            if (clip.id != lyricId) return@map clip
            clip.copy(
                startSec = safeStart,
                endSec = safeEnd,
                sourceType = LyricSourceType.USER_CORRECTED,
                uncertaintyNote = String.format(Locale.US, "User-verified timing %.2fs–%.2fs", safeStart, safeEnd),
                isUserCorrected = true
            )
        }.sortedBy { it.startSec }
        return edl.copy(lyrics = updated, revisionNumber = edl.revisionNumber + 1)
    }

    fun moveLyricOnTimeline(
        edl: ProjectEdl,
        lyricId: String,
        deltaSec: Float
    ): ProjectEdl {
        val maxDur = edl.totalEditedDurationSec
        val updated = edl.lyrics.map { clip ->
            if (clip.id != lyricId) return@map clip
            val dur = clip.durationSec
            val shiftedStart = (clip.startSec + deltaSec).coerceIn(0f, (maxDur - dur).coerceAtLeast(0f))
            val shiftedEnd = (shiftedStart + dur).coerceAtMost(maxDur)
            clip.copy(
                startSec = shiftedStart,
                endSec = shiftedEnd,
                sourceType = LyricSourceType.USER_CORRECTED,
                uncertaintyNote = String.format(Locale.US, "Moved on timeline (%.2fs–%.2fs)", shiftedStart, shiftedEnd),
                isUserCorrected = true
            )
        }.sortedBy { it.startSec }
        return edl.copy(lyrics = updated, revisionNumber = edl.revisionNumber + 1)
    }

    fun deleteLyricClip(edl: ProjectEdl, lyricId: String): ProjectEdl {
        val filtered = edl.lyrics.filterNot { it.id == lyricId }
        if (filtered.size == edl.lyrics.size) return edl
        return edl.copy(lyrics = filtered, revisionNumber = edl.revisionNumber + 1)
    }

    fun addUserCorrectedLyric(
        edl: ProjectEdl,
        atTimeSec: Float,
        text: String,
        durationSec: Float = 3.0f,
        isChorusHighlight: Boolean = false
    ): ProjectEdl {
        val maxDur = edl.totalEditedDurationSec
        val start = atTimeSec.coerceIn(0f, (maxDur - 0.5f).coerceAtLeast(0f))
        val end = (start + durationSec.coerceAtLeast(0.5f)).coerceAtMost(maxDur.coerceAtLeast(start + 0.5f))
        val newClip = EdlLyricClip(
            id = "lyric_user_${System.nanoTime()}",
            startSec = start,
            endSec = end,
            text = text.ifBlank { "New User-Corrected Lyric Line" },
            isChorusHighlight = isChorusHighlight,
            sourceType = LyricSourceType.USER_CORRECTED,
            uncertaintyNote = String.format(Locale.US, "User-added at %.2fs–%.2fs", start, end),
            isUserCorrected = true
        )
        val updated = (edl.lyrics + newClip).sortedBy { it.startSec }
        return edl.copy(lyrics = updated, revisionNumber = edl.revisionNumber + 1)
    }

    // =========================================================================
    // 4. NONDESTRUCTIVE AUDIO EDITING OPERATIONS
    // =========================================================================

    fun trimAudioRange(
        edl: ProjectEdl,
        newTrimStartSec: Float,
        newTrimEndSec: Float
    ): ProjectEdl {
        val maxDur = edl.sourceDurationSec.coerceAtLeast(2f)
        val safeStart = newTrimStartSec.coerceIn(0f, maxDur - 1f)
        val safeEnd = newTrimEndSec.coerceIn(safeStart + 1f, maxDur)
        val updatedEdl = edl.copy(
            trimStartSec = safeStart,
            trimEndSec = safeEnd,
            revisionNumber = edl.revisionNumber + 1
        )
        val reflowedScenes = reflowSceneBoundaries(updatedEdl.scenes, updatedEdl.totalEditedDurationSec)
        return updatedEdl.copy(scenes = reflowedScenes)
    }

    /**
     * Splits the audio segment containing [sourceSplitTimeSec] into two independent
     * [EdlAudioSegment]s without modifying the original MP3 file.
     */
    fun splitAudioAtSourceTimestamp(
        edl: ProjectEdl,
        sourceSplitTimeSec: Float
    ): ProjectEdl {
        val minSegLen = 0.4f
        val targetIdx = edl.audioSegments.indexOfFirst { seg ->
            sourceSplitTimeSec > seg.sourceStartSec + minSegLen &&
                sourceSplitTimeSec < seg.sourceEndSec - minSegLen
        }
        if (targetIdx < 0) return edl

        val target = edl.audioSegments[targetIdx]
        val left = EdlAudioSegment(
            id = "${target.id}_L_${edl.revisionNumber}",
            sourceStartSec = target.sourceStartSec,
            sourceEndSec = sourceSplitTimeSec,
            isRemoved = target.isRemoved
        )
        val right = EdlAudioSegment(
            id = "${target.id}_R_${edl.revisionNumber}",
            sourceStartSec = sourceSplitTimeSec,
            sourceEndSec = target.sourceEndSec,
            isRemoved = target.isRemoved
        )

        val mutable = edl.audioSegments.toMutableList()
        mutable.removeAt(targetIdx)
        mutable.add(targetIdx, right)
        mutable.add(targetIdx, left)

        return edl.copy(
            audioSegments = mutable,
            revisionNumber = edl.revisionNumber + 1
        )
    }

    /**
     * Nondestructively marks or unmarks an [EdlAudioSegment] as removed.
     * Guarantees at least one active audio segment remains so the project duration is never 0.
     */
    fun toggleRemoveAudioSegment(
        edl: ProjectEdl,
        segmentId: String
    ): ProjectEdl {
        val target = edl.audioSegments.firstOrNull { it.id == segmentId } ?: return edl
        if (!target.isRemoved) {
            val activeCount = edl.audioSegments.count { !it.isRemoved }
            if (activeCount <= 1) return edl // Must keep at least one active audio segment
        }

        val updatedSegments = edl.audioSegments.map { seg ->
            if (seg.id == segmentId) seg.copy(isRemoved = !seg.isRemoved) else seg
        }
        val intermediate = edl.copy(
            audioSegments = updatedSegments,
            revisionNumber = edl.revisionNumber + 1
        )
        val reflowedScenes = reflowSceneBoundaries(intermediate.scenes, intermediate.totalEditedDurationSec)
        return intermediate.copy(scenes = reflowedScenes)
    }

    /**
     * Maps an edited timeline timestamp (`0 .. edl.totalEditedDurationSec`) to the exact
     * timestamp in the original source audio file, skipping any trimmed or removed segments.
     */
    fun editedTimeToSourceTime(edl: ProjectEdl, editedTimeSec: Float): Float {
        val activeSegs = edl.activeAudioSegments
        if (activeSegs.isEmpty()) return edl.trimStartSec
        val clampedEdited = editedTimeSec.coerceIn(0f, edl.totalEditedDurationSec)

        var accumulatedEdited = 0f
        for (seg in activeSegs) {
            val segDur = seg.sourceDurationSec
            if (clampedEdited <= accumulatedEdited + segDur) {
                val offsetInSeg = (clampedEdited - accumulatedEdited).coerceIn(0f, segDur)
                return (seg.sourceStartSec + offsetInSeg).coerceIn(seg.sourceStartSec, seg.sourceEndSec)
            }
            accumulatedEdited += segDur
        }
        return activeSegs.last().sourceEndSec
    }

    /**
     * Maps a raw source audio timestamp (`0 .. edl.sourceDurationSec`) to the corresponding
     * edited timeline timestamp (`0 .. edl.totalEditedDurationSec`).
     */
    fun sourceTimeToEditedTime(edl: ProjectEdl, sourceTimeSec: Float): Float {
        val activeSegs = edl.activeAudioSegments
        if (activeSegs.isEmpty()) return 0f

        var accumulatedEdited = 0f
        for (seg in activeSegs) {
            if (sourceTimeSec < seg.sourceStartSec) {
                return accumulatedEdited
            }
            if (sourceTimeSec <= seg.sourceEndSec) {
                return accumulatedEdited + (sourceTimeSec - seg.sourceStartSec)
            }
            accumulatedEdited += seg.sourceDurationSec
        }
        return edl.totalEditedDurationSec
    }

    /**
     * If [sourceTimeSec] falls inside a removed segment or outside `[trimStartSec..trimEndSec]`,
     * returns the start of the next active audio segment (or wraps to the first active segment).
     * Returns `null` if [sourceTimeSec] is currently inside an active, non-removed segment.
     */
    fun nextActiveSourceTimeIfInCut(edl: ProjectEdl, sourceTimeSec: Float): Float? {
        val activeSegs = edl.activeAudioSegments
        if (activeSegs.isEmpty()) return null
        val insideActive = activeSegs.any { seg ->
            sourceTimeSec >= seg.sourceStartSec - 0.02f && sourceTimeSec <= seg.sourceEndSec - 0.02f
        }
        if (insideActive) return null

        val nextSeg = activeSegs.firstOrNull { it.sourceStartSec > sourceTimeSec }
        return nextSeg?.sourceStartSec ?: activeSegs.first().sourceStartSec
    }

    // =========================================================================
    // 5. EDIT HISTORY OPERATIONS (UNDO / REDO / RESTORE ORIGINAL)
    // =========================================================================

    fun recordEdit(
        currentEdl: ProjectEdl,
        newEdl: ProjectEdl,
        history: EdlHistoryStack
    ): Pair<ProjectEdl, EdlHistoryStack> {
        val updatedUndo = (history.undoStack + currentEdl).takeLast(40)
        return newEdl to history.copy(
            undoStack = updatedUndo,
            redoStack = emptyList()
        )
    }

    fun undo(
        currentEdl: ProjectEdl,
        history: EdlHistoryStack
    ): Pair<ProjectEdl, EdlHistoryStack> {
        if (history.undoStack.isEmpty()) return currentEdl to history
        val previous = history.undoStack.last()
        val preservedPrevious = mergeApprovedSceneVersions(previous, currentEdl)
        val remainingUndo = history.undoStack.dropLast(1)
        val updatedRedo = history.redoStack + currentEdl
        return preservedPrevious to history.copy(
            undoStack = remainingUndo,
            redoStack = updatedRedo
        )
    }

    fun redo(
        currentEdl: ProjectEdl,
        history: EdlHistoryStack
    ): Pair<ProjectEdl, EdlHistoryStack> {
        if (history.redoStack.isEmpty()) return currentEdl to history
        val next = history.redoStack.last()
        val preservedNext = mergeApprovedSceneVersions(next, currentEdl)
        val remainingRedo = history.redoStack.dropLast(1)
        val updatedUndo = history.undoStack + currentEdl
        return preservedNext to history.copy(
            undoStack = updatedUndo,
            redoStack = remainingRedo
        )
    }

    /**
     * Restores the original project state while preserving all approved scene version snapshots
     * generated during the session so the user never loses approved versions.
     */
    fun restoreOriginalProject(
        currentEdl: ProjectEdl,
        history: EdlHistoryStack
    ): Pair<ProjectEdl, EdlHistoryStack> {
        val restored = mergeApprovedSceneVersions(history.originalEdl, currentEdl)
        val updatedHistory = history.copy(
            undoStack = (history.undoStack + currentEdl).takeLast(40),
            redoStack = emptyList()
        )
        return restored to updatedHistory
    }

    private fun mergeApprovedSceneVersions(
        targetEdl: ProjectEdl,
        donorEdl: ProjectEdl
    ): ProjectEdl {
        val donorMap = donorEdl.scenes.associateBy { it.id }
        val mergedScenes = targetEdl.scenes.map { scene ->
            val donor = donorMap[scene.id] ?: return@map scene
            val combinedVersions = (scene.versions + donor.versions)
                .distinctBy { it.versionNumber to it.label }
                .sortedBy { it.versionNumber }
            val safeIdx = scene.activeVersionIndex.coerceIn(0, (combinedVersions.size - 1).coerceAtLeast(0))
            scene.copy(
                versions = combinedVersions,
                activeVersionIndex = safeIdx
            )
        }
        return targetEdl.copy(scenes = mergedScenes)
    }

    // =========================================================================
    // 6. UNIFIED PREVIEW & EXPORT RESOLUTION
    // =========================================================================

    /**
     * Resolves the exact visual & audio state at [editedTimeSec] from [edl].
     * Consumed identically by both `StudioPreviewScreen` and `HdVideoExporter`.
     */
    fun resolveRenderStateAt(
        edl: ProjectEdl,
        editedTimeSec: Float,
        fallbackCamera: CameraMovementPreset
    ): ResolvedEdlFrameState {
        val totalDur = edl.totalEditedDurationSec
        val clampedTime = editedTimeSec.coerceIn(0f, totalDur)
        val sourceTime = editedTimeToSourceTime(edl, clampedTime)

        val activeScene = edl.scenes.firstOrNull { clampedTime >= it.startSec && clampedTime <= it.endSec }
            ?: edl.scenes.minByOrNull { abs(clampedTime - ((it.startSec + it.endSec) * 0.5f)) }

        val sceneLocalProgress = if (activeScene != null) {
            ((clampedTime - activeScene.startSec) / activeScene.durationSec).coerceIn(0f, 1f)
        } else {
            (clampedTime / totalDur).coerceIn(0f, 1f)
        }

        val activeLyric = edl.lyrics.firstOrNull { clampedTime >= it.startSec && clampedTime <= it.endSec }

        return ResolvedEdlFrameState(
            editedTimeSec = clampedTime,
            totalEditedDurationSec = totalDur,
            sourceAudioTimeSec = sourceTime,
            activeScene = activeScene,
            sceneLocalProgress = sceneLocalProgress,
            activeLyric = activeLyric,
            effectiveCamera = activeScene?.cameraPreset ?: fallbackCamera,
            effectiveTransition = activeScene?.transitionType ?: EdlTransitionType.CROSS_DISSOLVE
        )
    }

    /**
     * Converts the [ProjectEdl] scenes into normalized [StoryboardCue] items so legacy callers
     * and `VideoFrameRenderer` remain 100% synchronized with all scene edits.
     */
    fun toStoryboardCues(edl: ProjectEdl): List<StoryboardCue> {
        val total = edl.totalEditedDurationSec.coerceAtLeast(1f)
        return edl.scenes.map { scene ->
            val startFrac = (scene.startSec / total).coerceIn(0f, 1f)
            val endFrac = (scene.endSec / total).coerceIn(startFrac, 1f)
            StoryboardCue(
                timeRangeLabel = String.format(Locale.US, "%d:%02d - %d:%02d",
                    scene.startSec.toInt() / 60, scene.startSec.toInt() % 60,
                    scene.endSec.toInt() / 60, scene.endSec.toInt() % 60
                ),
                startFraction = startFrac,
                endFraction = endFrac,
                sceneTitle = scene.sceneTitle,
                cameraNote = scene.cameraNote,
                lightingIntensity = scene.lightingIntensity,
                visualTransition = scene.transitionType.label,
                sectionType = scene.sectionType,
                vocalSectionTag = scene.vocalSectionTag,
                cameraPresetOverride = scene.cameraPreset,
                assetId = scene.assetId
            )
        }
    }

    private fun escapeJsonString(value: String): String = buildString(value.length + 8) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private fun nullableJsonString(value: String?): String =
        if (value == null) "null" else escapeJsonString(value)

    /**
     * Serializes [ProjectEdl] to JSON, referencing verified scene media solely via [EdlSceneClip.assetId]
     * and never storing filesystem paths.
     */
    fun serializeEdlToJson(edl: ProjectEdl): String = buildString {
        append('{')
        append("\"sourceDurationSec\":").append(edl.sourceDurationSec).append(',')
        append("\"trimStartSec\":").append(edl.trimStartSec).append(',')
        append("\"trimEndSec\":").append(edl.trimEndSec).append(',')
        append("\"revisionNumber\":").append(edl.revisionNumber).append(',')
        append("\"scenes\":[")
        edl.scenes.forEachIndexed { sIdx, scene ->
            if (sIdx > 0) append(',')
            append('{')
            append("\"id\":").append(escapeJsonString(scene.id)).append(',')
            append("\"startSec\":").append(scene.startSec).append(',')
            append("\"endSec\":").append(scene.endSec).append(',')
            append("\"sceneTitle\":").append(escapeJsonString(scene.sceneTitle)).append(',')
            append("\"cameraNote\":").append(escapeJsonString(scene.cameraNote)).append(',')
            append("\"cameraPreset\":").append(escapeJsonString(scene.cameraPreset.name)).append(',')
            append("\"transitionType\":").append(escapeJsonString(scene.transitionType.name)).append(',')
            append("\"lightingIntensity\":").append(scene.lightingIntensity).append(',')
            append("\"sectionType\":").append(escapeJsonString(scene.sectionType)).append(',')
            append("\"vocalSectionTag\":").append(escapeJsonString(scene.vocalSectionTag)).append(',')
            append("\"isApproved\":").append(scene.isApproved).append(',')
            append("\"activeVersionIndex\":").append(scene.activeVersionIndex).append(',')
            append("\"assetId\":").append(nullableJsonString(scene.assetId)).append(',')
            append("\"versions\":[")
            scene.versions.forEachIndexed { vIdx, v ->
                if (vIdx > 0) append(',')
                append('{')
                append("\"versionNumber\":").append(v.versionNumber).append(',')
                append("\"label\":").append(escapeJsonString(v.label)).append(',')
                append("\"sceneTitle\":").append(escapeJsonString(v.sceneTitle)).append(',')
                append("\"cameraNote\":").append(escapeJsonString(v.cameraNote)).append(',')
                append("\"cameraPreset\":").append(escapeJsonString(v.cameraPreset.name)).append(',')
                append("\"transitionType\":").append(escapeJsonString(v.transitionType.name)).append(',')
                append("\"lightingIntensity\":").append(v.lightingIntensity).append(',')
                append("\"sectionType\":").append(escapeJsonString(v.sectionType)).append(',')
                append("\"vocalSectionTag\":").append(escapeJsonString(v.vocalSectionTag)).append(',')
                append("\"isApproved\":").append(v.isApproved).append(',')
                append("\"assetId\":").append(nullableJsonString(v.assetId))
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    /**
     * Serializes [EdlHistoryStack] to JSON without any filesystem paths.
     */
    fun serializeHistoryToJson(history: EdlHistoryStack): String = buildString {
        append('{')
        append("\"originalEdl\":").append(serializeEdlToJson(history.originalEdl)).append(',')
        append("\"undoStack\":[")
        history.undoStack.forEachIndexed { idx, item ->
            if (idx > 0) append(',')
            append(serializeEdlToJson(item))
        }
        append("],\"redoStack\":[")
        history.redoStack.forEachIndexed { idx, item ->
            if (idx > 0) append(',')
            append(serializeEdlToJson(item))
        }
        append("]}")
    }
}
