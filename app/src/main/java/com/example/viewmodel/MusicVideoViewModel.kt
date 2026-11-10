package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.AiStoryboardRepository
import com.example.ai.AudioPayload
import com.example.ai.AudioPayloadReadResult
import com.example.ai.AudioPayloadValidator
import com.example.ai.FirebaseAiStoryboardRepository
import com.example.media.AudioPcmAnalyzer
import com.example.media.EdlHistoryStack
import com.example.media.EdlTimelineEngine
import com.example.media.ExportConfig
import com.example.media.HdVideoExporter
import com.example.media.SynthAudioGenerator
import com.example.model.AiStoryboardPlan
import com.example.model.AudioSource
import com.example.model.AudioSpectrumTimeline
import com.example.model.CameraMovementPreset
import com.example.model.EdlAudioSegment
import com.example.model.EdlLyricClip
import com.example.model.EdlSceneClip
import com.example.model.EdlTransitionType
import com.example.model.ExportedVideoItem
import com.example.model.LyricLine
import com.example.model.LyricSourceType
import com.example.model.PhotoSource
import com.example.model.ProjectEdl
import com.example.model.StudioSamplePresets
import com.example.model.VideoAspectRatio
import com.example.model.VideoModelType
import com.example.model.VideoResolution
import com.example.model.VisualFilterStyle
import com.example.studio.ingestion.AssetManager
import com.example.studio.storage.db.StudioDatabase
import com.example.studio.storage.workers.GenerationWorkOrchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MusicVideoViewModel"

enum class StudioDestination {
    CREATE,
    PREVIEW,
    EXPORTS
}

enum class EditorWorkstationTab(val label: String) {
    SCENES("Scenes"),
    LYRICS("Lyrics"),
    AUDIO("Audio")
}

private val defaultInitialEdl: ProjectEdl = EdlTimelineEngine.createInitialEdl(
    sourceDurationSec = 12f,
    trimStartSec = 0f,
    trimEndSec = 12f,
    defaultCamera = CameraMovementPreset.SLOW_PUSH_IN,
    storyboardPlan = null,
    initialLyrics = StudioUiState.demoCaptionsForBuiltInTrack(
        "Neon Horizon (Suno Studio Mix) • Nova Pulse",
        12f
    ),
    spectrumTimeline = null
)

data class StudioUiState(
    val currentTab: StudioDestination = StudioDestination.CREATE,
    val selectedPhoto: PhotoSource = PhotoSource.DrawableRes(
        resId = R.drawable.img_sample_cyberpunk_stage_1794279281846,
        title = "Cyberpunk Stage Backdrop"
    ),
    val loadedBitmap: Bitmap? = null,
    val selectedAudio: AudioSource = StudioSamplePresets.presets.first(),
    val musicLinkInput: String = "",
    val promptText: String = StudioSamplePresets.presets.first().defaultPrompt,
    val selectedModel: VideoModelType = VideoModelType.STANDARD,
    val selectedResolution: VideoResolution = VideoResolution.HD_720P,
    val selectedAspectRatio: VideoAspectRatio = VideoAspectRatio.RATIO_9_16,
    val selectedCamera: CameraMovementPreset = CameraMovementPreset.SLOW_PUSH_IN,
    val selectedFilter: VisualFilterStyle = VisualFilterStyle.SYNTHWAVE_NEON,
    val lyricsEnabled: Boolean = true,
    val showMoreOptionsSheet: Boolean = false,
    val fullSongDurationSec: Float = 12f,
    val exportFullSong: Boolean = true,
    val clipStartSec: Float = 0f,
    val clipEndSec: Float = 12f,
    val audioSpectrumTimeline: AudioSpectrumTimeline? = null,
    val isAnalyzingAudioPcm: Boolean = false,
    val isGeneratingStoryboard: Boolean = false,
    val storyboardPlan: AiStoryboardPlan? = null,
    val aiErrorMessage: String? = null,
    val isPlayingPreview: Boolean = false,
    val previewTimeSec: Float = 0f,
    val previewDurationSec: Float = 12f,
    val projectEdl: ProjectEdl = defaultInitialEdl,
    val edlHistory: EdlHistoryStack = EdlHistoryStack(originalEdl = defaultInitialEdl),
    val selectedWorkstationTab: EditorWorkstationTab = EditorWorkstationTab.SCENES,
    val selectedSceneId: String? = defaultInitialEdl.scenes.firstOrNull()?.id,
    val selectedLyricId: String? = defaultInitialEdl.lyrics.firstOrNull()?.id,
    val selectedAudioSegmentId: String? = defaultInitialEdl.audioSegments.firstOrNull()?.id,
    val isIngestingSceneAsset: Boolean = false,
    val assetIngestionStatusMessage: String? = null,
    val assetIngestionErrorMessage: String? = null,
    val isExportingVideo: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStatusText: String = "",
    val exportedVideos: List<ExportedVideoItem> = emptyList(),
    val statusSnackbarMessage: String? = null
) {
    val activeSongTitle: String
        get() = when (val src = selectedAudio) {
            is AudioSource.LocalMp3Uri -> src.displayName
            is AudioSource.MusicLink -> src.parsedTitle
            is AudioSource.BuiltInSynthTrack -> "${src.title} • ${src.artist}"
        }

    val effectiveClipStartSec: Float
        get() = if (projectEdl !== defaultInitialEdl) {
            projectEdl.trimStartSec
        } else {
            if (exportFullSong) 0f else clipStartSec.coerceIn(0f, (fullSongDurationSec - 1f).coerceAtLeast(0f))
        }

    val effectiveClipEndSec: Float
        get() = if (projectEdl !== defaultInitialEdl) {
            projectEdl.trimEndSec
        } else {
            if (exportFullSong) fullSongDurationSec else clipEndSec.coerceIn(effectiveClipStartSec + 1f, fullSongDurationSec)
        }

    val exportDurationSec: Int
        get() = if (projectEdl !== defaultInitialEdl) {
            projectEdl.totalEditedDurationSec.toInt().coerceAtLeast(1)
        } else {
            (effectiveClipEndSec - effectiveClipStartSec).toInt().coerceAtLeast(1)
        }

    val selectedSceneClip: EdlSceneClip?
        get() = projectEdl.scenes.firstOrNull { it.id == selectedSceneId }
            ?: projectEdl.scenes.firstOrNull()

    val selectedLyricClip: EdlLyricClip?
        get() = projectEdl.lyrics.firstOrNull { it.id == selectedLyricId }
            ?: projectEdl.lyrics.firstOrNull()

    val selectedAudioSegment: EdlAudioSegment?
        get() = projectEdl.audioSegments.firstOrNull { it.id == selectedAudioSegmentId }
            ?: projectEdl.audioSegments.firstOrNull()

    /**
     * Never invents generic demonstration sentences for imported songs.
     * Returns the current approved EDL lyrics (user-corrected, AI-transcribed, or built-in demo captions).
     */
    val activeLyrics: List<LyricLine>
        get() {
            if (projectEdl !== defaultInitialEdl) {
                return projectEdl.lyrics.map { it.toLyricLine() }
            }
            val aiLyrics = storyboardPlan?.syncedLyrics
            if (!aiLyrics.isNullOrEmpty()) return aiLyrics
            return when (selectedAudio) {
                is AudioSource.BuiltInSynthTrack -> projectEdl.lyrics.map { it.toLyricLine() }
                is AudioSource.LocalMp3Uri, is AudioSource.MusicLink -> emptyList()
            }
        }

    companion object {
        fun demoCaptionsForBuiltInTrack(songTitle: String, durationSec: Float): List<LyricLine> {
            val step = (durationSec / 3f).coerceAtLeast(2.5f)
            return listOf(
                LyricLine(
                    startSec = 0f,
                    endSec = step,
                    text = "♫ $songTitle (Built-In Demo Track)",
                    isChorusHighlight = false,
                    sourceType = LyricSourceType.SYNTH_DEMO_CAPTION,
                    uncertaintyNote = "Built-in studio demonstration caption"
                ),
                LyricLine(
                    startSec = step,
                    endSec = step * 2f,
                    text = "★ Chorus Drop — PCM Spectrum Visualizer Active ★",
                    isChorusHighlight = true,
                    sourceType = LyricSourceType.SYNTH_DEMO_CAPTION,
                    uncertaintyNote = "Built-in studio demonstration caption"
                ),
                LyricLine(
                    startSec = step * 2f,
                    endSec = durationSec,
                    text = "Tap 'AI Storyboard' for live Gemini scene cues",
                    isChorusHighlight = false,
                    sourceType = LyricSourceType.SYNTH_DEMO_CAPTION,
                    uncertaintyNote = "Built-in studio demonstration caption"
                )
            )
        }
    }
}

class MusicVideoViewModel(
    private val aiRepository: AiStoryboardRepository = FirebaseAiStoryboardRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var playbackTickerJob: Job? = null
    private var exportJob: Job? = null

    fun initializeDefaultAssets(context: Context) {
        try {
            GenerationWorkOrchestrator.enqueueStartupStorageRecovery(context)
        } catch (e: Exception) {
            Log.w(TAG, "WorkManager startup recovery schedule warning", e)
        }
        if (_uiState.value.loadedBitmap == null) {
            loadBitmapFromSource(context, _uiState.value.selectedPhoto)
        }
        if (_uiState.value.audioSpectrumTimeline == null) {
            analyzeCurrentAudioSpectrum(context, _uiState.value.selectedAudio)
        }
    }

    private fun rebuildProjectEdlFromCurrentState(
        sourceDurationSec: Float,
        trimStartSec: Float = 0f,
        trimEndSec: Float = sourceDurationSec,
        defaultCamera: CameraMovementPreset = _uiState.value.selectedCamera,
        storyboardPlan: AiStoryboardPlan? = _uiState.value.storyboardPlan,
        audioSource: AudioSource = _uiState.value.selectedAudio,
        songTitle: String = _uiState.value.activeSongTitle,
        spectrumTimeline: AudioSpectrumTimeline? = _uiState.value.audioSpectrumTimeline
    ): ProjectEdl {
        val baseLyrics = storyboardPlan?.syncedLyrics?.takeIf { it.isNotEmpty() }
            ?: when (audioSource) {
                is AudioSource.BuiltInSynthTrack ->
                    StudioUiState.demoCaptionsForBuiltInTrack(songTitle, sourceDurationSec)
                is AudioSource.LocalMp3Uri, is AudioSource.MusicLink ->
                    emptyList()
            }
        return EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = sourceDurationSec,
            trimStartSec = trimStartSec,
            trimEndSec = trimEndSec,
            defaultCamera = defaultCamera,
            storyboardPlan = storyboardPlan,
            initialLyrics = baseLyrics,
            spectrumTimeline = spectrumTimeline
        )
    }

    private fun commitEdlEdit(newEdl: ProjectEdl, snackbarMsg: String? = null) {
        _uiState.update { state ->
            val (committedEdl, updatedHistory) = EdlTimelineEngine.recordEdit(
                currentEdl = state.projectEdl,
                newEdl = newEdl,
                history = state.edlHistory
            )
            val validSceneId = state.selectedSceneId?.takeIf { id -> committedEdl.scenes.any { it.id == id } }
                ?: committedEdl.scenes.firstOrNull()?.id
            val validLyricId = state.selectedLyricId?.takeIf { id -> committedEdl.lyrics.any { it.id == id } }
                ?: committedEdl.lyrics.firstOrNull()?.id
            val validAudioSegId = state.selectedAudioSegmentId?.takeIf { id -> committedEdl.audioSegments.any { it.id == id } }
                ?: committedEdl.audioSegments.firstOrNull()?.id

            state.copy(
                projectEdl = committedEdl,
                edlHistory = updatedHistory,
                clipStartSec = committedEdl.trimStartSec,
                clipEndSec = committedEdl.trimEndSec,
                previewDurationSec = committedEdl.totalEditedDurationSec,
                previewTimeSec = state.previewTimeSec.coerceIn(0f, committedEdl.totalEditedDurationSec),
                selectedSceneId = validSceneId,
                selectedLyricId = validLyricId,
                selectedAudioSegmentId = validAudioSegId,
                statusSnackbarMessage = snackbarMsg ?: state.statusSnackbarMessage
            )
        }
    }

    // =========================================================================
    // EDIT HISTORY CONTROLS (UNDO / REDO / RESTORE ORIGINAL)
    // =========================================================================

    fun undoEdlEdit() {
        val state = _uiState.value
        if (!state.edlHistory.canUndo) return
        val (restoredEdl, updatedHistory) = EdlTimelineEngine.undo(state.projectEdl, state.edlHistory)
        _uiState.update {
            it.copy(
                projectEdl = restoredEdl,
                edlHistory = updatedHistory,
                clipStartSec = restoredEdl.trimStartSec,
                clipEndSec = restoredEdl.trimEndSec,
                previewDurationSec = restoredEdl.totalEditedDurationSec,
                previewTimeSec = it.previewTimeSec.coerceIn(0f, restoredEdl.totalEditedDurationSec),
                selectedSceneId = restoredEdl.scenes.firstOrNull()?.id,
                selectedLyricId = restoredEdl.lyrics.firstOrNull()?.id,
                selectedAudioSegmentId = restoredEdl.audioSegments.firstOrNull()?.id,
                statusSnackbarMessage = "Undid last timeline edit."
            )
        }
    }

    fun redoEdlEdit() {
        val state = _uiState.value
        if (!state.edlHistory.canRedo) return
        val (nextEdl, updatedHistory) = EdlTimelineEngine.redo(state.projectEdl, state.edlHistory)
        _uiState.update {
            it.copy(
                projectEdl = nextEdl,
                edlHistory = updatedHistory,
                clipStartSec = nextEdl.trimStartSec,
                clipEndSec = nextEdl.trimEndSec,
                previewDurationSec = nextEdl.totalEditedDurationSec,
                previewTimeSec = it.previewTimeSec.coerceIn(0f, nextEdl.totalEditedDurationSec),
                selectedSceneId = nextEdl.scenes.firstOrNull()?.id,
                selectedLyricId = nextEdl.lyrics.firstOrNull()?.id,
                selectedAudioSegmentId = nextEdl.audioSegments.firstOrNull()?.id,
                statusSnackbarMessage = "Redid timeline edit."
            )
        }
    }

    fun restoreOriginalEdl() {
        val state = _uiState.value
        val (restoredEdl, updatedHistory) = EdlTimelineEngine.restoreOriginalProject(
            currentEdl = state.projectEdl,
            history = state.edlHistory
        )
        _uiState.update {
            it.copy(
                projectEdl = restoredEdl,
                edlHistory = updatedHistory,
                exportFullSong = true,
                clipStartSec = restoredEdl.trimStartSec,
                clipEndSec = restoredEdl.trimEndSec,
                previewDurationSec = restoredEdl.totalEditedDurationSec,
                previewTimeSec = 0f,
                selectedSceneId = restoredEdl.scenes.firstOrNull()?.id,
                selectedLyricId = restoredEdl.lyrics.firstOrNull()?.id,
                selectedAudioSegmentId = restoredEdl.audioSegments.firstOrNull()?.id,
                statusSnackbarMessage = "Restored original project (approved scene versions preserved)."
            )
        }
        seekPreview(0f)
    }

    fun selectWorkstationTab(tab: EditorWorkstationTab) {
        _uiState.update { it.copy(selectedWorkstationTab = tab) }
    }

    // =========================================================================
    // VIDEO SCENE EDITING ACTIONS
    // =========================================================================

    fun selectScene(sceneId: String, jumpPlayhead: Boolean = true) {
        val scene = _uiState.value.projectEdl.scenes.firstOrNull { it.id == sceneId }
        _uiState.update {
            it.copy(
                selectedSceneId = sceneId,
                selectedWorkstationTab = EditorWorkstationTab.SCENES
            )
        }
        if (jumpPlayhead && scene != null) {
            seekPreview(scene.startSec + 0.05f)
        }
    }

    fun deleteSelectedScene() {
        val state = _uiState.value
        val sceneId = state.selectedSceneClip?.id ?: return
        if (state.projectEdl.scenes.size <= 1) {
            _uiState.update { it.copy(statusSnackbarMessage = "At least one video scene is required on the timeline.") }
            return
        }
        val updated = EdlTimelineEngine.deleteScene(state.projectEdl, sceneId)
        commitEdlEdit(updated, "Scene removed and timeline boundaries reflowed.")
    }

    fun replaceSelectedScene(
        newTitle: String,
        newSectionType: String,
        newCameraPreset: CameraMovementPreset,
        newTransition: EdlTransitionType,
        newLightingIntensity: Float,
        newCameraNote: String
    ) {
        val state = _uiState.value
        val sceneId = state.selectedSceneClip?.id ?: return
        val updated = EdlTimelineEngine.replaceScene(
            edl = state.projectEdl,
            sceneId = sceneId,
            newTitle = newTitle,
            newSectionType = newSectionType,
            newCameraPreset = newCameraPreset,
            newTransition = newTransition,
            newLightingIntensity = newLightingIntensity,
            newCameraNote = newCameraNote
        )
        commitEdlEdit(updated, "Replaced scene '${newTitle.ifBlank { "Scene" }}' (new version saved).")
    }

    fun addSceneAfterSelected() {
        val state = _uiState.value
        val afterId = state.selectedSceneClip?.id
        val updated = EdlTimelineEngine.addSceneAfter(state.projectEdl, afterId)
        commitEdlEdit(updated, "Inserted new scene into Video Scene Track.")
    }

    fun reorderSelectedScene(directionDelta: Int) {
        val state = _uiState.value
        val sceneId = state.selectedSceneClip?.id ?: return
        val updated = EdlTimelineEngine.reorderScene(state.projectEdl, sceneId, directionDelta)
        if (updated != state.projectEdl) {
            commitEdlEdit(updated, "Reordered scene on timeline.")
        }
    }

    fun changeSelectedSceneDuration(newDurationSec: Float) {
        val state = _uiState.value
        val sceneId = state.selectedSceneClip?.id ?: return
        val updated = EdlTimelineEngine.changeSceneDuration(state.projectEdl, sceneId, newDurationSec)
        commitEdlEdit(updated)
    }

    fun updateSelectedSceneCamera(cameraPreset: CameraMovementPreset) {
        val state = _uiState.value
        val sceneId = state.selectedSceneClip?.id ?: return
        val updated = EdlTimelineEngine.updateSceneCamera(state.projectEdl, sceneId, cameraPreset)
        commitEdlEdit(updated, "Updated scene camera to ${cameraPreset.label}.")
    }

    fun updateSelectedSceneTransition(transitionType: EdlTransitionType) {
        val state = _uiState.value
        val sceneId = state.selectedSceneClip?.id ?: return
        val updated = EdlTimelineEngine.updateSceneTransition(state.projectEdl, sceneId, transitionType)
        commitEdlEdit(updated, "Updated scene transition to ${transitionType.label}.")
    }

    fun regenerateSelectedScene() {
        val state = _uiState.value
        val scene = state.selectedSceneClip ?: return
        val updated = EdlTimelineEngine.regenerateSingleScene(state.projectEdl, scene.id)
        val newScene = updated.scenes.firstOrNull { it.id == scene.id }
        val verLabel = newScene?.versions?.lastOrNull()?.label ?: "new version"
        commitEdlEdit(updated, "Regenerated scene '${scene.sceneTitle}' -> $verLabel (previous versions preserved).")
    }

    fun restoreSelectedSceneVersion(versionIndex: Int) {
        val state = _uiState.value
        val scene = state.selectedSceneClip ?: return
        val updated = EdlTimelineEngine.restoreSceneVersion(state.projectEdl, scene.id, versionIndex)
        val verLabel = scene.versions.getOrNull(versionIndex)?.label ?: "v${versionIndex + 1}"
        commitEdlEdit(updated, "Restored scene version $verLabel.")
    }

    /**
     * Ingests a local `.mp4` video clip via the Phase 2C Durable Local Import & Promotion Pipeline:
     * Photo Picker -> Persistable URI permission or durable Room job (`jobKind = LOCAL_IMPORT`,
     * `sourceUri`, `sourceUriGrantFlags`) -> `LocalImportWorker` (`Data` containing only `jobId`) ->
     * `.staging_tmp/job_<jobId>.part` -> Streaming SHA-256 (`ChecksumVerifier`) ->
     * Bounded Streaming `MediaExtractorProbe` + Separate `FrameDecodabilityVerifier` ->
     * Promotion Journal (`PREPARED -> PROMOTED -> COMMITTED`) + `RoomDatabase.withTransaction` ->
     * `AssetDescriptor` -> `ProjectEdl` (`assetId` only, zero filesystem paths).
     */
    fun onSceneLocalMp4Picked(context: Context, uri: Uri) {
        val state = _uiState.value
        if (state.isIngestingSceneAsset) return
        val scene = state.selectedSceneClip ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isIngestingSceneAsset = true,
                    assetIngestionStatusMessage = "Staging, hashing (SHA-256), and validating MP4 container for '${scene.sceneTitle}'…",
                    assetIngestionErrorMessage = null
                )
            }

            var persistedGrantFlags: Int? = null
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                persistedGrantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            } catch (_: SecurityException) {
                // Photo Picker URIs that do not support takePersistableUriPermission are staged immediately
            } catch (_: Exception) {
            }

            val database = StudioDatabase.getInstance(context)
            com.example.media.SceneVideoFrameExtractor.registerAssetStorageRoot(
                baseFilesDir = context.filesDir,
                database = database
            )
            val assetManager = AssetManager(
                baseFilesDir = context.filesDir,
                database = database
            )

            val jobId = "job_local_${scene.id}_${System.currentTimeMillis()}"
            val nowMs = System.currentTimeMillis()
            val idempotencyKey = assetManager.computeIdempotencyKey(
                sceneId = scene.id,
                providerId = "local_import",
                modelId = "local_mp4_ingest",
                prompt = "Local MP4 import for scene ${scene.id}",
                seedOrSourceKey = "${uri}|$nowMs"
            )
            database.generationJobDao().insertJobIfAbsent(
                com.example.studio.storage.db.GenerationJobEntity(
                    jobId = jobId,
                    idempotencyKey = idempotencyKey,
                    sceneId = scene.id,
                    providerId = "local_import",
                    modelId = "local_mp4_ingest",
                    prompt = "Local MP4 import for scene ${scene.id}",
                    status = com.example.studio.storage.db.GenerationJobStatus.QUEUED,
                    submissionAttemptedAtMs = null,
                    providerOperationId = null,
                    resultAssetId = null,
                    errorMessage = null,
                    attemptCount = 0,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                    jobKind = com.example.studio.storage.db.GenerationJobKind.LOCAL_IMPORT,
                    sourceUri = uri.toString(),
                    sourceUriGrantFlags = persistedGrantFlags ?: 0
                )
            )

            // Schedule durable background execution via LocalImportWorker (ExistingWorkPolicy.KEEP)
            try {
                GenerationWorkOrchestrator.enqueueLocalImportWork(context, jobId)
            } catch (e: Exception) {
                Log.w(TAG, "LocalImportWorker enqueue warning (executing inline fallback)", e)
            }

            val result = assetManager.ingestLocalMp4ForJob(
                jobId = jobId,
                sceneId = scene.id,
                sourceUri = uri,
                context = context
            )

            result.fold(
                onSuccess = { descriptor ->
                    val vfrBadge = if (descriptor.isVfr) "VFR" else "CFR"
                    val badge = "${descriptor.assetId} ($vfrBadge ${descriptor.nominalFps.toInt()}fps)"

                    val currentEdl = _uiState.value.projectEdl
                    val updatedEdl = EdlTimelineEngine.attachVerifiedAssetToScene(
                        edl = currentEdl,
                        sceneId = scene.id,
                        assetId = descriptor.assetId,
                        versionBadge = badge
                    )
                    _uiState.update {
                        it.copy(
                            isIngestingSceneAsset = false,
                            assetIngestionErrorMessage = null,
                            assetIngestionStatusMessage = "Promoted ${descriptor.assetId} (${descriptor.width}x${descriptor.height}, ${descriptor.durationMs / 1000}s, $vfrBadge, SHA-256 ${descriptor.sha256Hex.take(12)}…)."
                        )
                    }
                    commitEdlEdit(
                        updatedEdl,
                        "Ingested & verified ${descriptor.assetId} into scene '${scene.sceneTitle}'."
                    )
                },
                onFailure = { err ->
                    Log.e(TAG, "Failed to ingest scene MP4 asset", err)
                    _uiState.update {
                        it.copy(
                            isIngestingSceneAsset = false,
                            assetIngestionStatusMessage = null,
                            assetIngestionErrorMessage = "Asset Validation Rejected: ${err.localizedMessage ?: "Invalid MP4 container."}"
                        )
                    }
                }
            )
        }
    }

    // =========================================================================
    // LYRIC / CAPTION EDITING ACTIONS
    // =========================================================================

    fun selectLyric(lyricId: String, jumpPlayhead: Boolean = true) {
        val clip = _uiState.value.projectEdl.lyrics.firstOrNull { it.id == lyricId }
        _uiState.update {
            it.copy(
                selectedLyricId = lyricId,
                selectedWorkstationTab = EditorWorkstationTab.LYRICS
            )
        }
        if (jumpPlayhead && clip != null) {
            seekPreview(clip.startSec + 0.05f)
        }
    }

    fun updateSelectedLyricText(newText: String, isChorusHighlight: Boolean) {
        val state = _uiState.value
        val lyricId = state.selectedLyricClip?.id ?: return
        val updated = EdlTimelineEngine.updateLyricLine(
            edl = state.projectEdl,
            lyricId = lyricId,
            newText = newText,
            isChorusHighlight = isChorusHighlight
        )
        commitEdlEdit(updated, "Updated & verified lyric line.")
    }

    fun adjustSelectedLyricTimestamps(newStartSec: Float, newEndSec: Float) {
        val state = _uiState.value
        val lyricId = state.selectedLyricClip?.id ?: return
        val updated = EdlTimelineEngine.updateLyricTimestamps(
            edl = state.projectEdl,
            lyricId = lyricId,
            newStartSec = newStartSec,
            newEndSec = newEndSec
        )
        commitEdlEdit(updated)
    }

    fun nudgeSelectedLyricOnTimeline(deltaSec: Float) {
        val state = _uiState.value
        val lyricId = state.selectedLyricClip?.id ?: return
        val updated = EdlTimelineEngine.moveLyricOnTimeline(
            edl = state.projectEdl,
            lyricId = lyricId,
            deltaSec = deltaSec
        )
        commitEdlEdit(updated)
    }

    fun deleteSelectedLyric() {
        val state = _uiState.value
        val lyricId = state.selectedLyricClip?.id ?: return
        val updated = EdlTimelineEngine.deleteLyricClip(state.projectEdl, lyricId)
        commitEdlEdit(updated, "Removed caption from Lyrics Track.")
    }

    fun addUserCorrectedLyricAtPlayhead(text: String, isChorusHighlight: Boolean) {
        val state = _uiState.value
        val updated = EdlTimelineEngine.addUserCorrectedLyric(
            edl = state.projectEdl,
            atTimeSec = state.previewTimeSec,
            text = text,
            durationSec = 3.0f,
            isChorusHighlight = isChorusHighlight
        )
        val addedClip = updated.lyrics.maxByOrNull { it.id }
        commitEdlEdit(updated, "Added user-corrected lyric at ${String.format(Locale.US, "%.1fs", state.previewTimeSec)}.")
        if (addedClip != null) {
            _uiState.update { it.copy(selectedLyricId = addedClip.id) }
        }
    }

    // =========================================================================
    // NONDESTRUCTIVE AUDIO EDITING ACTIONS
    // =========================================================================

    fun selectAudioSegment(segmentId: String, jumpPlayhead: Boolean = true) {
        val state = _uiState.value
        val seg = state.projectEdl.audioSegments.firstOrNull { it.id == segmentId }
        _uiState.update {
            it.copy(
                selectedAudioSegmentId = segmentId,
                selectedWorkstationTab = EditorWorkstationTab.AUDIO
            )
        }
        if (jumpPlayhead && seg != null && !seg.isRemoved) {
            val editedStart = EdlTimelineEngine.sourceTimeToEditedTime(state.projectEdl, seg.sourceStartSec)
            seekPreview(editedStart)
        }
    }

    fun trimEdlAudioRange(newTrimStartSec: Float, newTrimEndSec: Float) {
        val state = _uiState.value
        val updated = EdlTimelineEngine.trimAudioRange(
            edl = state.projectEdl,
            newTrimStartSec = newTrimStartSec,
            newTrimEndSec = newTrimEndSec
        )
        _uiState.update {
            it.copy(
                exportFullSong = (updated.trimStartSec <= 0.05f && updated.trimEndSec >= updated.sourceDurationSec - 0.05f)
            )
        }
        commitEdlEdit(updated)
    }

    fun splitAudioAtPlayhead() {
        val state = _uiState.value
        val sourceSplitTime = EdlTimelineEngine.editedTimeToSourceTime(state.projectEdl, state.previewTimeSec)
        val updated = EdlTimelineEngine.splitAudioAtSourceTimestamp(state.projectEdl, sourceSplitTime)
        if (updated == state.projectEdl) {
            _uiState.update {
                it.copy(statusSnackbarMessage = "Move playhead away from segment edges (at least 0.4s) to split audio.")
            }
            return
        }
        commitEdlEdit(
            updated,
            String.format(Locale.US, "Split audio track nondestructively at %.2fs.", sourceSplitTime)
        )
    }

    fun toggleRemoveSelectedAudioSegment() {
        val state = _uiState.value
        val seg = state.selectedAudioSegment ?: return
        toggleRemoveAudioSegmentById(seg.id)
    }

    fun toggleRemoveAudioSegmentById(segmentId: String) {
        val state = _uiState.value
        val target = state.projectEdl.audioSegments.firstOrNull { it.id == segmentId } ?: return
        if (!target.isRemoved && state.projectEdl.audioSegments.count { !it.isRemoved } <= 1) {
            _uiState.update {
                it.copy(statusSnackbarMessage = "Cannot remove the only active audio segment.")
            }
            return
        }
        val updated = EdlTimelineEngine.toggleRemoveAudioSegment(state.projectEdl, segmentId)
        val actionLabel = if (target.isRemoved) "Restored audio segment" else "Removed audio segment nondestructively"
        commitEdlEdit(updated, "$actionLabel (${String.format(Locale.US, "%.1fs–%.1fs", target.sourceStartSec, target.sourceEndSec)}).")
    }

    fun previewSelectedAudioSegment(context: Context) {
        val state = _uiState.value
        val seg = state.selectedAudioSegment ?: return
        if (seg.isRemoved) {
            _uiState.update { it.copy(statusSnackbarMessage = "Restore this segment first to preview it in the timeline.") }
            return
        }
        val editedStart = EdlTimelineEngine.sourceTimeToEditedTime(state.projectEdl, seg.sourceStartSec)
        seekPreview(editedStart)
        if (!state.isPlayingPreview) {
            startPreviewPlayback(context)
        }
    }

    // =========================================================================
    // EXISTING STUDIO NAVIGATION & ASSET LOADING (PRESERVED)
    // =========================================================================

    fun selectTab(tab: StudioDestination, context: Context) {
        if (tab != StudioDestination.PREVIEW && _uiState.value.isPlayingPreview) {
            pausePreviewPlayback()
        }
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == StudioDestination.PREVIEW) {
            startPreviewPlayback(context)
        }
    }

    fun onPhotoPicked(context: Context, uri: Uri) {
        val displayName = queryFileName(context, uri) ?: "Selected Photo"
        val source = PhotoSource.LocalUri(uri, displayName)
        _uiState.update {
            it.copy(
                selectedPhoto = source,
                statusSnackbarMessage = "Photo loaded: $displayName"
            )
        }
        loadBitmapFromSource(context, source)
    }

    fun selectSamplePhoto(context: Context, resId: Int, title: String) {
        val source = PhotoSource.DrawableRes(resId, title)
        _uiState.update {
            it.copy(
                selectedPhoto = source,
                statusSnackbarMessage = "Selected studio photo: $title"
            )
        }
        loadBitmapFromSource(context, source)
    }

    private fun loadBitmapFromSource(context: Context, source: PhotoSource) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = when (source) {
                    is PhotoSource.DrawableRes -> {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeResource(context.resources, source.resId, opts)
                    }
                    is PhotoSource.LocalUri -> {
                        context.contentResolver.openInputStream(source.uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }
                }
                if (bmp != null) {
                    _uiState.update { it.copy(loadedBitmap = bmp) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode photo bitmap", e)
                _uiState.update {
                    it.copy(statusSnackbarMessage = "Could not load photo: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Decodes PCM audio samples for the active [AudioSource] in the background so both live Preview
     * and HD MP4 Export drive their 24-band visualizer and timeline waveform from actual PCM amplitudes.
     */
    private fun analyzeCurrentAudioSpectrum(context: Context, audioSource: AudioSource) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isAnalyzingAudioPcm = true) }
            when (audioSource) {
                is AudioSource.LocalMp3Uri -> {
                    val result = AudioPcmAnalyzer.decodeAndAnalyzeUri(
                        context = context,
                        audioUri = audioSource.uri
                    )
                    result.fold(
                        onSuccess = { timeline ->
                            val exactDur = timeline.durationSec.takeIf { it >= 1f }
                                ?: (audioSource.durationMs / 1000f).coerceAtLeast(1f)
                            val peaks = EdlTimelineEngine.extractWaveformPeaks(timeline)
                            _uiState.update { state ->
                                val newEdl = rebuildProjectEdlFromCurrentState(
                                    sourceDurationSec = exactDur,
                                    trimStartSec = 0f,
                                    trimEndSec = exactDur,
                                    defaultCamera = state.selectedCamera,
                                    storyboardPlan = state.storyboardPlan,
                                    audioSource = audioSource,
                                    songTitle = audioSource.displayName,
                                    spectrumTimeline = timeline
                                ).copy(waveformPeaks = peaks)

                                state.copy(
                                    isAnalyzingAudioPcm = false,
                                    audioSpectrumTimeline = timeline,
                                    fullSongDurationSec = exactDur,
                                    previewDurationSec = newEdl.totalEditedDurationSec,
                                    clipStartSec = 0f,
                                    clipEndSec = exactDur,
                                    projectEdl = newEdl,
                                    edlHistory = EdlHistoryStack(originalEdl = newEdl),
                                    selectedSceneId = newEdl.scenes.firstOrNull()?.id,
                                    selectedLyricId = newEdl.lyrics.firstOrNull()?.id,
                                    selectedAudioSegmentId = newEdl.audioSegments.firstOrNull()?.id
                                )
                            }
                        },
                        onFailure = { err ->
                            Log.e(TAG, "Failed to decode MP3 PCM for visualizer", err)
                            _uiState.update {
                                it.copy(
                                    isAnalyzingAudioPcm = false,
                                    audioSpectrumTimeline = null,
                                    statusSnackbarMessage = "Audio decode warning: ${err.localizedMessage}"
                                )
                            }
                        }
                    )
                }
                is AudioSource.BuiltInSynthTrack -> {
                    val durSec = (audioSource.durationMs / 1000L).toInt().coerceAtLeast(6)
                    val pcm = SynthAudioGenerator.generatePcmSamples(
                        presetIndex = audioSource.synthPresetIndex,
                        durationSec = durSec
                    )
                    val timeline = AudioPcmAnalyzer.analyzePcmSamples(
                        pcmSamples = pcm,
                        sampleRate = SynthAudioGenerator.SAMPLE_RATE
                    )
                    val peaks = EdlTimelineEngine.extractWaveformPeaks(timeline)
                    _uiState.update { state ->
                        val updatedEdl = state.projectEdl.copy(waveformPeaks = peaks)
                        state.copy(
                            isAnalyzingAudioPcm = false,
                            audioSpectrumTimeline = timeline,
                            projectEdl = updatedEdl,
                            edlHistory = state.edlHistory.copy(
                                originalEdl = state.edlHistory.originalEdl.copy(waveformPeaks = peaks)
                            )
                        )
                    }
                }
                is AudioSource.MusicLink -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzingAudioPcm = false,
                            audioSpectrumTimeline = null
                        )
                    }
                }
            }
        }
    }

    fun onAudioMp3Picked(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = queryFileName(context, uri) ?: "Imported_Audio_Track.mp3"
            var durationMs = 0L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durStr?.toLongOrNull()?.let {
                    if (it > 0L) durationMs = it
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever duration read failed, will use PCM decoded duration", e)
            }

            val initialSec = if (durationMs > 0L) (durationMs / 1000f).coerceAtLeast(1f) else 15f
            val localSource = AudioSource.LocalMp3Uri(uri, fileName, if (durationMs > 0L) durationMs else 15_000L)
            val initialEdl = EdlTimelineEngine.createInitialEdl(
                sourceDurationSec = initialSec,
                trimStartSec = 0f,
                trimEndSec = initialSec,
                defaultCamera = _uiState.value.selectedCamera,
                storyboardPlan = null,
                initialLyrics = emptyList(),
                spectrumTimeline = null
            )

            _uiState.update {
                it.copy(
                    selectedAudio = localSource,
                    musicLinkInput = "",
                    fullSongDurationSec = initialSec,
                    previewDurationSec = initialEdl.totalEditedDurationSec,
                    exportFullSong = true,
                    clipStartSec = 0f,
                    clipEndSec = initialSec,
                    previewTimeSec = 0f,
                    storyboardPlan = null,
                    projectEdl = initialEdl,
                    edlHistory = EdlHistoryStack(originalEdl = initialEdl),
                    selectedSceneId = initialEdl.scenes.firstOrNull()?.id,
                    selectedLyricId = initialEdl.lyrics.firstOrNull()?.id,
                    selectedAudioSegmentId = initialEdl.audioSegments.firstOrNull()?.id,
                    statusSnackbarMessage = "Loaded MP3: $fileName (${initialSec.toInt()}s full song)"
                )
            }
            analyzeCurrentAudioSpectrum(context, localSource)
            runAiStoryboardAnalysis(context)
        }
    }

    fun onMusicLinkChanged(newLink: String) {
        val trimmed = newLink.trim()
        val updatedAudio = if (trimmed.isNotEmpty()) {
            val parsedName = parseTitleFromMusicLink(trimmed)
            AudioSource.MusicLink(
                url = trimmed,
                parsedTitle = parsedName,
                synthPresetIndex = (trimmed.hashCode().and(0x7FFFFFFF)) % 3,
                durationMs = 12_000L
            )
        } else {
            _uiState.value.selectedAudio
        }

        _uiState.update {
            it.copy(
                musicLinkInput = newLink,
                selectedAudio = updatedAudio
            )
        }
    }

    fun applyMusicLink(context: Context) {
        val link = _uiState.value.musicLinkInput.trim()
        if (link.isBlank()) {
            _uiState.update { it.copy(statusSnackbarMessage = "Paste a Suno or direct audio URL first.") }
            return
        }
        val parsedName = parseTitleFromMusicLink(link)
        val audio = AudioSource.MusicLink(
            url = link,
            parsedTitle = parsedName,
            synthPresetIndex = (link.hashCode().and(0x7FFFFFFF)) % 3,
            durationMs = 12_000L
        )
        val initialEdl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = 12f,
            trimStartSec = 0f,
            trimEndSec = 12f,
            defaultCamera = _uiState.value.selectedCamera,
            storyboardPlan = null,
            initialLyrics = emptyList(),
            spectrumTimeline = null
        )
        _uiState.update {
            it.copy(
                selectedAudio = audio,
                fullSongDurationSec = 12f,
                previewDurationSec = initialEdl.totalEditedDurationSec,
                exportFullSong = true,
                clipStartSec = 0f,
                clipEndSec = 12f,
                previewTimeSec = 0f,
                storyboardPlan = null,
                projectEdl = initialEdl,
                edlHistory = EdlHistoryStack(originalEdl = initialEdl),
                selectedSceneId = initialEdl.scenes.firstOrNull()?.id,
                selectedLyricId = initialEdl.lyrics.firstOrNull()?.id,
                selectedAudioSegmentId = initialEdl.audioSegments.firstOrNull()?.id,
                statusSnackbarMessage = "Loaded Music Link: $parsedName"
            )
        }
        analyzeCurrentAudioSpectrum(context, audio)
        runAiStoryboardAnalysis(context)
    }

    fun selectPresetTrack(context: Context, preset: AudioSource.BuiltInSynthTrack) {
        val photoSource = PhotoSource.DrawableRes(preset.defaultPhotoRes, preset.title)
        val durSec = (preset.durationMs / 1000f).coerceAtLeast(6f)
        val demoLyrics = StudioUiState.demoCaptionsForBuiltInTrack("${preset.title} • ${preset.artist}", durSec)
        val initialEdl = EdlTimelineEngine.createInitialEdl(
            sourceDurationSec = durSec,
            trimStartSec = 0f,
            trimEndSec = durSec,
            defaultCamera = _uiState.value.selectedCamera,
            storyboardPlan = null,
            initialLyrics = demoLyrics,
            spectrumTimeline = null
        )
        _uiState.update {
            it.copy(
                selectedAudio = preset,
                selectedPhoto = photoSource,
                musicLinkInput = "",
                promptText = preset.defaultPrompt,
                fullSongDurationSec = durSec,
                previewDurationSec = initialEdl.totalEditedDurationSec,
                exportFullSong = true,
                clipStartSec = 0f,
                clipEndSec = durSec,
                previewTimeSec = 0f,
                storyboardPlan = null,
                projectEdl = initialEdl,
                edlHistory = EdlHistoryStack(originalEdl = initialEdl),
                selectedSceneId = initialEdl.scenes.firstOrNull()?.id,
                selectedLyricId = initialEdl.lyrics.firstOrNull()?.id,
                selectedAudioSegmentId = initialEdl.audioSegments.firstOrNull()?.id,
                statusSnackbarMessage = "Loaded studio preset: ${preset.title}"
            )
        }
        loadBitmapFromSource(context, photoSource)
        analyzeCurrentAudioSpectrum(context, preset)
    }

    fun onPromptChanged(newPrompt: String) {
        if (newPrompt.length <= 2000) {
            _uiState.update { it.copy(promptText = newPrompt) }
        }
    }

    fun selectModel(model: VideoModelType) {
        _uiState.update { it.copy(selectedModel = model) }
    }

    fun selectResolution(resolution: VideoResolution) {
        _uiState.update { it.copy(selectedResolution = resolution) }
    }

    fun selectAspectRatio(ratio: VideoAspectRatio) {
        _uiState.update { it.copy(selectedAspectRatio = ratio) }
    }

    fun selectCameraPreset(camera: CameraMovementPreset) {
        _uiState.update { it.copy(selectedCamera = camera) }
        val selectedSceneId = _uiState.value.selectedSceneClip?.id
        if (selectedSceneId != null) {
            updateSelectedSceneCamera(camera)
        }
    }

    fun selectFilterStyle(filter: VisualFilterStyle) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun setExportFullSong(fullSong: Boolean) {
        val state = _uiState.value
        val newStart = if (fullSong) 0f else state.clipStartSec
        val newEnd = if (fullSong) state.fullSongDurationSec else state.clipEndSec
        _uiState.update { it.copy(exportFullSong = fullSong) }
        trimEdlAudioRange(newStart, newEnd)
    }

    fun setClipRange(startSec: Float, endSec: Float) {
        _uiState.update { it.copy(exportFullSong = false) }
        trimEdlAudioRange(startSec, endSec)
    }

    fun toggleLyrics(enabled: Boolean) {
        _uiState.update { it.copy(lyricsEnabled = enabled) }
    }

    fun setShowMoreOptions(show: Boolean) {
        _uiState.update { it.copy(showMoreOptionsSheet = show) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(statusSnackbarMessage = null) }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _uiState.update {
            it.copy(
                isExportingVideo = false,
                exportProgress = 0f,
                exportStatusText = "",
                statusSnackbarMessage = "HD Video export cancelled."
            )
        }
    }

    /**
     * Executes a real Gemini inference request via Firebase AI Logic.
     * Enforces Priority 6 (Audio Payload Safety): validates MIME type and total file size before
     * reading bytes, never silently truncates an imported MP3, and reports oversized or unsupported
     * audio files transparently.
     */
    fun runAiStoryboardAnalysis(context: Context? = null) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGeneratingStoryboard = true,
                    aiErrorMessage = null
                )
            }
            try {
                val payloadResult = if (context != null) {
                    readAndValidateAudioPayload(context, snapshot.selectedAudio)
                } else {
                    null
                }

                val validatedPayload: AudioPayload? = when (payloadResult) {
                    is AudioPayloadReadResult.ValidInlinePayload -> payloadResult.payload
                    is AudioPayloadReadResult.ExceedsInlineLimit -> {
                        val sizeMb = payloadResult.actualSizeBytes / (1024 * 1024)
                        val maxMb = payloadResult.maxAllowedBytes / (1024 * 1024)
                        _uiState.update {
                            it.copy(
                                isGeneratingStoryboard = false,
                                aiErrorMessage = "Audio Payload Limit Exceeded: The selected audio file is ${sizeMb} MB, which exceeds the ${maxMb} MB inline analysis limit. Music Video Studio refuses to silently truncate your track. Full-song local playback and HD MP4 export remain available."
                            )
                        }
                        return@launch
                    }
                    is AudioPayloadReadResult.UnsupportedMimeType -> {
                        _uiState.update {
                            it.copy(
                                isGeneratingStoryboard = false,
                                aiErrorMessage = "Unsupported Audio Format for AI Analysis: '${payloadResult.mimeType}'. Supported formats: MP3, WAV, AAC, M4A, OGG, FLAC."
                            )
                        }
                        return@launch
                    }
                    is AudioPayloadReadResult.ReadError -> {
                        _uiState.update {
                            it.copy(
                                isGeneratingStoryboard = false,
                                aiErrorMessage = "Audio Read Error: ${payloadResult.message}"
                            )
                        }
                        return@launch
                    }
                    null -> null
                }

                val plan = aiRepository.generateStoryboard(
                    rawPrompt = snapshot.promptText,
                    songTitle = snapshot.activeSongTitle,
                    durationSec = snapshot.fullSongDurationSec,
                    lyricsEnabled = snapshot.lyricsEnabled,
                    photoBitmap = snapshot.loadedBitmap,
                    audioPayload = validatedPayload
                )
                _uiState.update { current ->
                    val newEdl = EdlTimelineEngine.createInitialEdl(
                        sourceDurationSec = current.fullSongDurationSec,
                        trimStartSec = current.projectEdl.trimStartSec,
                        trimEndSec = current.projectEdl.trimEndSec,
                        defaultCamera = plan.suggestedCamera,
                        storyboardPlan = plan,
                        initialLyrics = plan.syncedLyrics,
                        spectrumTimeline = current.audioSpectrumTimeline
                    )
                    current.copy(
                        isGeneratingStoryboard = false,
                        storyboardPlan = plan,
                        aiErrorMessage = null,
                        selectedCamera = plan.suggestedCamera,
                        selectedFilter = plan.suggestedFilter,
                        projectEdl = newEdl,
                        edlHistory = EdlHistoryStack(originalEdl = newEdl),
                        selectedSceneId = newEdl.scenes.firstOrNull()?.id,
                        selectedLyricId = newEdl.lyrics.firstOrNull()?.id,
                        selectedAudioSegmentId = newEdl.audioSegments.firstOrNull()?.id,
                        statusSnackbarMessage = "Gemini AI Storyboard & EDL Timeline ready!"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live Gemini storyboard analysis failed", e)
                val diagnosticMessage = classifyAiError(e)
                _uiState.update {
                    it.copy(
                        isGeneratingStoryboard = false,
                        aiErrorMessage = diagnosticMessage
                    )
                }
            }
        }
    }

    fun generateAndOpenPreview(context: Context) {
        _uiState.update {
            it.copy(
                currentTab = StudioDestination.PREVIEW,
                previewTimeSec = 0f
            )
        }
        startPreviewPlayback(context)

        if (_uiState.value.storyboardPlan == null && !_uiState.value.isGeneratingStoryboard) {
            runAiStoryboardAnalysis(context)
        }
    }

    /**
     * Validates MIME type and exact file size before reading bytes for Gemini inline submission.
     * Never silently truncates an MP3 file.
     */
    internal suspend fun readAndValidateAudioPayload(
        context: Context,
        audioSource: AudioSource
    ): AudioPayloadReadResult? = withContext(Dispatchers.IO) {
        try {
            when (audioSource) {
                is AudioSource.LocalMp3Uri -> {
                    val rawMime = context.contentResolver.getType(audioSource.uri)
                    val normalizedMime = AudioPayloadValidator.normalizeMimeType(rawMime, audioSource.displayName)
                    if (!AudioPayloadValidator.isSupportedAudioMime(normalizedMime)) {
                        return@withContext AudioPayloadReadResult.UnsupportedMimeType(normalizedMime)
                    }

                    val declaredSize = queryFileSize(context, audioSource.uri)
                    if (declaredSize != null && declaredSize > AudioPayloadValidator.MAX_INLINE_AUDIO_BYTES) {
                        return@withContext AudioPayloadReadResult.ExceedsInlineLimit(
                            actualSizeBytes = declaredSize,
                            maxAllowedBytes = AudioPayloadValidator.MAX_INLINE_AUDIO_BYTES
                        )
                    }

                    val out = ByteArrayOutputStream()
                    var totalRead = 0L
                    context.contentResolver.openInputStream(audioSource.uri)?.use { stream ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val r = stream.read(chunk)
                            if (r <= 0) break
                            totalRead += r
                            if (totalRead > AudioPayloadValidator.MAX_INLINE_AUDIO_BYTES) {
                                return@withContext AudioPayloadReadResult.ExceedsInlineLimit(
                                    actualSizeBytes = declaredSize ?: totalRead,
                                    maxAllowedBytes = AudioPayloadValidator.MAX_INLINE_AUDIO_BYTES
                                )
                            }
                            out.write(chunk, 0, r)
                        }
                    } ?: return@withContext AudioPayloadReadResult.ReadError("Could not open input stream for selected MP3.")

                    val bytes = out.toByteArray()
                    if (bytes.isEmpty()) {
                        return@withContext AudioPayloadReadResult.ReadError("Selected audio file is empty (0 bytes).")
                    }

                    AudioPayloadReadResult.ValidInlinePayload(
                        AudioPayload(
                            bytes = bytes,
                            mimeType = normalizedMime,
                            totalFileSizeBytes = bytes.size.toLong(),
                            isCompleteTrackBytes = true,
                            isRealImportedMp3 = true
                        )
                    )
                }
                is AudioSource.BuiltInSynthTrack -> {
                    val wavFile = ensureSynthWavFile(context, audioSource.synthPresetIndex, (audioSource.durationMs / 1000L).toInt())
                    if (wavFile.exists() && wavFile.length() <= AudioPayloadValidator.MAX_INLINE_AUDIO_BYTES) {
                        val bytes = wavFile.readBytes()
                        AudioPayloadReadResult.ValidInlinePayload(
                            AudioPayload(
                                bytes = bytes,
                                mimeType = "audio/wav",
                                totalFileSizeBytes = bytes.size.toLong(),
                                isCompleteTrackBytes = true,
                                isRealImportedMp3 = false
                            )
                        )
                    } else null
                }
                is AudioSource.MusicLink -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error validating audio payload for AI analysis", e)
            AudioPayloadReadResult.ReadError(e.localizedMessage ?: "Failed to read audio file.")
        }
    }

    private fun classifyAiError(e: Exception): String {
        val raw = e.localizedMessage ?: e.message ?: "Unknown AI error"
        val lower = raw.lowercase()
        return when {
            "403" in lower || "app check" in lower || "attestation" in lower || "permission" in lower ->
                "Authentication / App Check Error (HTTP 403): $raw"
            "429" in lower || "quota" in lower || "rate limit" in lower || "resource_exhausted" in lower ->
                "Quota / Rate Limit Exceeded (HTTP 429): $raw"
            "timeout" in lower || "unable to resolve host" in lower || "network" in lower || "connect" in lower ->
                "Network Connectivity Error: $raw"
            else -> "Gemini AI Error: $raw"
        }
    }

    fun togglePreviewPlayback(context: Context) {
        if (_uiState.value.isPlayingPreview) {
            pausePreviewPlayback()
        } else {
            startPreviewPlayback(context)
        }
    }

    /**
     * Seeks along the edited timeline (`0 .. projectEdl.totalEditedDurationSec`) and maps
     * that edited timestamp to the underlying source audio timestamp on [MediaPlayer].
     */
    fun seekPreview(newEditedTimeSec: Float) {
        val state = _uiState.value
        val edl = state.projectEdl
        val clampedEdited = newEditedTimeSec.coerceIn(0f, edl.totalEditedDurationSec)
        val mappedSourceSec = EdlTimelineEngine.editedTimeToSourceTime(edl, clampedEdited)
        _uiState.update { it.copy(previewTimeSec = clampedEdited) }
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying || player.duration > 0) {
                    val targetMs = (mappedSourceSec * 1000).toInt().coerceIn(0, player.duration.coerceAtLeast(1))
                    player.seekTo(targetMs)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun startPreviewPlayback(context: Context) {
        pausePreviewPlayback()
        val state = _uiState.value
        _uiState.update { it.copy(isPlayingPreview = true, aiErrorMessage = it.aiErrorMessage) }

        val initialSourceSec = EdlTimelineEngine.editedTimeToSourceTime(
            state.projectEdl,
            state.previewTimeSec
        )

        viewModelScope.launch {
            prepareAndStartMediaPlayer(context, state.selectedAudio, initialSourceSec)
        }

        playbackTickerJob = viewModelScope.launch {
            val frameStepSec = 0.033f
            while (isActive && _uiState.value.isPlayingPreview) {
                delay(33L)
                val current = _uiState.value
                val edl = current.projectEdl
                val totalEditedDur = edl.totalEditedDurationSec.coerceAtLeast(1f)

                val rawPlayerSourceSec = try {
                    mediaPlayer?.takeIf { it.isPlaying }?.currentPosition?.div(1000f)
                } catch (_: Exception) {
                    null
                }

                if (rawPlayerSourceSec != null) {
                    // Check if playback entered a removed segment or exceeded trimEndSec
                    val jumpTargetSourceSec = EdlTimelineEngine.nextActiveSourceTimeIfInCut(edl, rawPlayerSourceSec)
                    val effectiveSourceSec = if (jumpTargetSourceSec != null) {
                        try {
                            mediaPlayer?.seekTo((jumpTargetSourceSec * 1000).toInt())
                            mediaPlayer?.start()
                        } catch (_: Exception) {
                        }
                        jumpTargetSourceSec
                    } else {
                        rawPlayerSourceSec
                    }
                    val mappedEditedSec = EdlTimelineEngine.sourceTimeToEditedTime(edl, effectiveSourceSec)
                    _uiState.update { it.copy(previewTimeSec = mappedEditedSec.coerceIn(0f, totalEditedDur)) }
                } else {
                    val nextEdited = current.previewTimeSec + frameStepSec
                    if (nextEdited >= totalEditedDur) {
                        _uiState.update { it.copy(previewTimeSec = 0f) }
                    } else {
                        _uiState.update { it.copy(previewTimeSec = nextEdited) }
                    }
                }
            }
        }
    }

    fun pausePreviewPlayback() {
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        _uiState.update { it.copy(isPlayingPreview = false) }
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {
        }
    }

    private suspend fun prepareAndStartMediaPlayer(
        context: Context,
        audioSource: AudioSource,
        startSourceSec: Float
    ) = withContext(Dispatchers.IO) {
        try {
            mediaPlayer?.release()
            mediaPlayer = null

            val player = MediaPlayer()
            when (audioSource) {
                is AudioSource.LocalMp3Uri -> {
                    player.setDataSource(context, audioSource.uri)
                }
                is AudioSource.MusicLink -> {
                    player.setDataSource(audioSource.url)
                }
                is AudioSource.BuiltInSynthTrack -> {
                    val wavFile = ensureSynthWavFile(
                        context,
                        audioSource.synthPresetIndex,
                        (audioSource.durationMs / 1000L).toInt().coerceAtLeast(6)
                    )
                    player.setDataSource(wavFile.absolutePath)
                }
            }
            player.isLooping = true
            player.prepare()
            val seekMs = (startSourceSec * 1000).toInt().coerceIn(0, player.duration.coerceAtLeast(1))
            player.seekTo(seekMs)
            player.start()
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed to play selected audio source", e)
            _uiState.update {
                it.copy(
                    isPlayingPreview = false,
                    statusSnackbarMessage = "Audio playback error: ${e.localizedMessage ?: "Cannot play selected audio source."}"
                )
            }
        }
    }

    private fun ensureSynthWavFile(context: Context, presetIndex: Int, durationSec: Int = 12): File {
        val audioCacheDir = File(context.cacheDir, "synth_audio").apply { mkdirs() }
        val safeDur = durationSec.coerceIn(4, 30)
        val wavFile = File(audioCacheDir, "studio_synth_preset_${presetIndex % 3}_${safeDur}s.wav")
        if (!wavFile.exists() || wavFile.length() < 1024L) {
            val samples = SynthAudioGenerator.generatePcmSamples(presetIndex = presetIndex, durationSec = safeDur)
            SynthAudioGenerator.writeWavFile(wavFile, samples)
        }
        return wavFile
    }

    /**
     * Full-song or edited-EDL HD MP4 Exporter.
     * Consumes the exact same approved [ProjectEdl] displayed in the Preview Editing Workstation.
     */
    fun exportHdVideo(context: Context) {
        val state = _uiState.value
        if (state.isExportingVideo) return

        val bitmapToUse = state.loadedBitmap ?: BitmapFactory.decodeResource(
            context.resources,
            R.drawable.img_sample_cyberpunk_stage_1794279281846
        )
        if (bitmapToUse == null) {
            _uiState.update { it.copy(statusSnackbarMessage = "Please add a photo before exporting.") }
            return
        }

        pausePreviewPlayback()

        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExportingVideo = true,
                    exportProgress = 0.01f,
                    exportStatusText = "Preparing local HD video & audio encoder from approved EDL…"
                )
            }

            // Ensure PCM spectrum timeline is computed before exporting
            val timeline = state.audioSpectrumTimeline ?: when (val src = state.selectedAudio) {
                is AudioSource.LocalMp3Uri -> AudioPcmAnalyzer.decodeAndAnalyzeUri(context, src.uri).getOrNull()
                is AudioSource.BuiltInSynthTrack -> {
                    val pcm = SynthAudioGenerator.generatePcmSamples(
                        src.synthPresetIndex,
                        (src.durationMs / 1000L).toInt().coerceAtLeast(6)
                    )
                    AudioPcmAnalyzer.analyzePcmSamples(pcm, SynthAudioGenerator.SAMPLE_RATE)
                }
                is AudioSource.MusicLink -> null
            }

            val approvedEdl = state.projectEdl
            val config = ExportConfig(
                sourceBitmap = bitmapToUse,
                audioSource = state.selectedAudio,
                modelType = state.selectedModel,
                resolution = state.selectedResolution,
                aspectRatio = state.selectedAspectRatio,
                cameraPreset = state.selectedCamera,
                filterStyle = state.selectedFilter,
                songTitle = state.activeSongTitle,
                promptText = state.promptText,
                lyricsEnabled = state.lyricsEnabled,
                lyrics = state.activeLyrics,
                storyboardCues = EdlTimelineEngine.toStoryboardCues(approvedEdl),
                spectrumTimeline = timeline,
                clipStartSec = approvedEdl.trimStartSec,
                clipEndSec = approvedEdl.trimEndSec,
                fps = 24,
                projectEdl = approvedEdl
            )

            val result = HdVideoExporter.exportToMp4(context, config) { progress, message ->
                _uiState.update {
                    it.copy(
                        exportProgress = progress,
                        exportStatusText = message
                    )
                }
            }

            result.fold(
                onSuccess = { mp4File ->
                    val galleryResult = HdVideoExporter.saveToMoviesGallery(
                        context = context,
                        mp4File = mp4File,
                        title = state.activeSongTitle
                    )
                    val (w, h) = state.selectedAspectRatio.calculateDimensions(state.selectedResolution)
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)
                    val item = ExportedVideoItem(
                        id = mp4File.nameWithoutExtension,
                        filePath = mp4File.absolutePath,
                        title = state.activeSongTitle,
                        resolutionLabel = "${state.selectedResolution.label.replace("Resolution ", "")} (${w}×${h})",
                        aspectRatioLabel = state.selectedAspectRatio.label.replace("Aspect ratio ", ""),
                        durationSec = approvedEdl.totalEditedDurationSec.toInt().coerceAtLeast(1),
                        fileSizeKb = (mp4File.length() / 1024L).coerceAtLeast(1L),
                        createdAtFormatted = dateFormat.format(Date()),
                        savedToGalleryUri = galleryResult.getOrNull()?.toString()
                    )
                    _uiState.update { current ->
                        current.copy(
                            isExportingVideo = false,
                            exportProgress = 1f,
                            exportStatusText = "Exported ${mp4File.name}",
                            exportedVideos = listOf(item) + current.exportedVideos,
                            currentTab = StudioDestination.EXPORTS,
                            statusSnackbarMessage = "HD MP4 Video exported (${item.durationSec}s, ${item.fileSizeKb} KB) & saved to Movies!"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isExportingVideo = false,
                            exportProgress = 0f,
                            exportStatusText = "",
                            statusSnackbarMessage = "Export failed: ${error.localizedMessage ?: "Could not decode/mux audio or encode MP4"}"
                        )
                    }
                }
            )
        }
    }

    fun shareExportedVideo(context: Context, item: ExportedVideoItem) {
        try {
            val file = File(item.filePath)
            if (!file.exists()) {
                _uiState.update { it.copy(statusSnackbarMessage = "Video file not found on disk.") }
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                putExtra(Intent.EXTRA_TEXT, "Created with Music Video Studio (${item.resolutionLabel})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Share HD Music Video").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share exported video", e)
            _uiState.update {
                it.copy(statusSnackbarMessage = "Unable to open share sheet: ${e.localizedMessage}")
            }
        }
    }

    private fun parseTitleFromMusicLink(url: String): String {
        val clean = url.substringBefore("?").trimEnd('/')
        val lastSegment = clean.substringAfterLast('/')
        return when {
            "suno" in url.lowercase() -> {
                val idPart = lastSegment.take(8).ifBlank { "Track" }
                "Suno AI Song ($idPart)"
            }
            lastSegment.isNotBlank() && lastSegment.length <= 36 -> {
                lastSegment.replace("-", " ").replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
            else -> "Linked Studio Audio Track"
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackTickerJob?.cancel()
        exportJob?.cancel()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }
}
