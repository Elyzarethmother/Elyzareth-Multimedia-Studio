package com.example.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.media.EdlTimelineEngine
import com.example.model.CameraMovementPreset
import com.example.model.EdlTransitionType
import com.example.model.ProjectEdl
import com.example.ui.theme.CyberLime
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCardDark
import com.example.ui.theme.StudioCardElevated
import com.example.ui.theme.StudioSurfaceDark
import com.example.ui.theme.SuccessEmerald
import com.example.ui.theme.TextPrimaryWhite
import com.example.ui.theme.TextSecondaryMuted
import com.example.viewmodel.EditorWorkstationTab
import com.example.viewmodel.MusicVideoViewModel
import com.example.viewmodel.StudioUiState
import java.util.Locale

private val ErrorCoral = Color(0xFFFF4D6D)

@Composable
fun EditorialWorkstationSection(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel,
    context: Context,
    modifier: Modifier = Modifier
) {
    val edl = uiState.projectEdl

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Edit History & Nondestructive EDL Status Bar
        EdlHistoryToolbar(
            uiState = uiState,
            onUndo = { viewModel.undoEdlEdit() },
            onRedo = { viewModel.redoEdlEdit() },
            onRestoreOriginal = { viewModel.restoreOriginalEdl() }
        )

        // 2. Multi-Track Interactive Timeline (Video Scenes + Audio Waveform + Lyrics + Transitions)
        MultiTrackTimelineCard(
            edl = edl,
            playheadSec = uiState.previewTimeSec,
            selectedSceneId = uiState.selectedSceneClip?.id,
            selectedLyricId = uiState.selectedLyricClip?.id,
            selectedAudioSegmentId = uiState.selectedAudioSegment?.id,
            onSeekPlayhead = { sec -> viewModel.seekPreview(sec) },
            onSelectScene = { id -> viewModel.selectScene(id) },
            onSelectLyric = { id -> viewModel.selectLyric(id) }
        )

        // 3. Workstation Track Inspector Selector (Scenes | Lyrics | Audio)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditorWorkstationTab.entries.forEach { tab ->
                val isSelected = uiState.selectedWorkstationTab == tab
                val countBadge = when (tab) {
                    EditorWorkstationTab.SCENES -> "${edl.scenes.size}"
                    EditorWorkstationTab.LYRICS -> "${edl.lyrics.size}"
                    EditorWorkstationTab.AUDIO -> "${edl.activeAudioSegments.size}/${edl.audioSegments.size}"
                }
                val icon = when (tab) {
                    EditorWorkstationTab.SCENES -> Icons.Default.Movie
                    EditorWorkstationTab.LYRICS -> Icons.Default.Subtitles
                    EditorWorkstationTab.AUDIO -> Icons.Default.GraphicEq
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewModel.selectWorkstationTab(tab) }
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) CyberLime else StudioBorderSubtle,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .testTag("workstation_tab_${tab.name.lowercase()}"),
                    color = if (isSelected) ElectricIndigo else StudioCardDark
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) CyberLime else TextSecondaryMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${tab.label} ($countBadge)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) TextPrimaryWhite else TextSecondaryMuted,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 4. Active Track Inspector Panel
        when (uiState.selectedWorkstationTab) {
            EditorWorkstationTab.SCENES -> VideoSceneEditorPanel(
                uiState = uiState,
                viewModel = viewModel,
                context = context
            )
            EditorWorkstationTab.LYRICS -> LyricTrackEditorPanel(
                uiState = uiState,
                viewModel = viewModel
            )
            EditorWorkstationTab.AUDIO -> AudioTrackEditorPanel(
                uiState = uiState,
                viewModel = viewModel,
                context = context
            )
        }
    }
}

@Composable
private fun EdlHistoryToolbar(
    uiState: StudioUiState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRestoreOriginal: () -> Unit
) {
    val edl = uiState.projectEdl
    val history = uiState.edlHistory

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = StudioCardDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nondestructive EDL Workstation • Rev #${edl.revisionNumber}",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberLime,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Edited Duration: %.1fs (Source: %.1fs) • %d Scenes • %d Lyrics",
                        edl.totalEditedDurationSec,
                        edl.sourceDurationSec,
                        edl.scenes.size,
                        edl.lyrics.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryMuted
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onUndo,
                    enabled = history.canUndo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("edl_undo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo Edit",
                        tint = if (history.canUndo) TextPrimaryWhite else TextSecondaryMuted.copy(alpha = 0.35f)
                    )
                }

                IconButton(
                    onClick = onRedo,
                    enabled = history.canRedo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("edl_redo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo Edit",
                        tint = if (history.canRedo) TextPrimaryWhite else TextSecondaryMuted.copy(alpha = 0.35f)
                    )
                }

                IconButton(
                    onClick = onRestoreOriginal,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("edl_restore_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = "Restore Original Project",
                        tint = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiTrackTimelineCard(
    edl: ProjectEdl,
    playheadSec: Float,
    selectedSceneId: String?,
    selectedLyricId: String?,
    selectedAudioSegmentId: String?,
    onSeekPlayhead: (Float) -> Unit,
    onSelectScene: (String) -> Unit,
    onSelectLyric: (String) -> Unit
) {
    val totalEditedDur = edl.totalEditedDurationSec.coerceAtLeast(1f)
    val totalSourceDur = edl.sourceDurationSec.coerceAtLeast(1f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = StudioCardDark
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PROJECT TIMELINE (SCENES • AUDIO WAVEFORM • LYRICS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryMuted,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Playhead: %.1fs / %.1fs",
                        playheadSec,
                        totalEditedDur
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberLime
                )
            }

            // Interactive 3-Track Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(138.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioSurfaceDark)
                    .pointerInput(edl, totalEditedDur) {
                        detectTapGestures { offset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val frac = (offset.x / w).coerceIn(0f, 1f)
                            val tappedEditedSec = frac * totalEditedDur
                            onSeekPlayhead(tappedEditedSec)

                            val trackRowHeight = h / 3f
                            if (offset.y < trackRowHeight) {
                                // Tapped Video Scene Track
                                val hitScene = edl.scenes.firstOrNull {
                                    tappedEditedSec >= it.startSec && tappedEditedSec <= it.endSec
                                }
                                if (hitScene != null) {
                                    onSelectScene(hitScene.id)
                                }
                            } else if (offset.y > trackRowHeight * 2f) {
                                // Tapped Lyrics Track
                                val hitLyric = edl.lyrics.firstOrNull {
                                    tappedEditedSec >= it.startSec && tappedEditedSec <= it.endSec
                                }
                                if (hitLyric != null) {
                                    onSelectLyric(hitLyric.id)
                                }
                            }
                        }
                    }
                    .testTag("edl_multitrack_timeline_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(138.dp)) {
                    val w = size.width
                    val h = size.height
                    val trackH = h / 3f

                    // Track Separator Lines
                    drawLine(
                        color = Color(0xFF252238),
                        start = Offset(0f, trackH),
                        end = Offset(w, trackH),
                        strokeWidth = 1.5f
                    )
                    drawLine(
                        color = Color(0xFF252238),
                        start = Offset(0f, trackH * 2f),
                        end = Offset(w, trackH * 2f),
                        strokeWidth = 1.5f
                    )

                    // TRACK 1: VIDEO SCENE TRACK + TRANSITION DIAMONDS
                    edl.scenes.forEachIndexed { idx, scene ->
                        val xStart = (scene.startSec / totalEditedDur) * w
                        val xEnd = (scene.endSec / totalEditedDur) * w
                        val segW = (xEnd - xStart - 2f).coerceAtLeast(4f)
                        val isSelected = scene.id == selectedSceneId

                        val fillColor = when {
                            isSelected -> Color(0xFF6B3DF5)
                            "CHORUS" in scene.sectionType.uppercase() -> Color(0xFF4B28B5)
                            else -> Color(0xFF2D2556)
                        }
                        drawRoundRect(
                            color = fillColor,
                            topLeft = Offset(xStart + 1f, 4f),
                            size = Size(segW, trackH - 8f),
                            cornerRadius = CornerRadius(8f, 8f)
                        )
                        if (isSelected) {
                            drawRoundRect(
                                color = Color(0xFFA8F240),
                                topLeft = Offset(xStart + 1f, 4f),
                                size = Size(segW, trackH - 8f),
                                cornerRadius = CornerRadius(8f, 8f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                            )
                        }

                        // Transition diamond marker at scene boundary
                        if (idx > 0) {
                            val dSize = 7f
                            val cy = trackH * 0.5f
                            val path = Path().apply {
                                moveTo(xStart, cy - dSize)
                                lineTo(xStart + dSize, cy)
                                lineTo(xStart, cy + dSize)
                                lineTo(xStart - dSize, cy)
                                close()
                            }
                            drawPath(path, color = Color(0xFF00E5FF))
                        }
                    }

                    // TRACK 2: ORIGINAL AUDIO TRACK + ACTUAL WAVEFORM PEAKS + SPLIT/REMOVED REGIONS
                    val audioTop = trackH
                    val audioBottom = trackH * 2f
                    val audioMidY = (audioTop + audioBottom) * 0.5f

                    // Render audio segments (active vs removed)
                    edl.audioSegments.forEachIndexed { idx, seg ->
                        val sX = (seg.sourceStartSec / totalSourceDur) * w
                        val eX = (seg.sourceEndSec / totalSourceDur) * w
                        val segW = (eX - sX).coerceAtLeast(2f)
                        val isSegSelected = seg.id == selectedAudioSegmentId

                        val segBg = when {
                            seg.isRemoved -> Color(0x55FF4D6D)
                            isSegSelected -> Color(0x4400E5FF)
                            else -> Color(0x2200E5FF)
                        }
                        drawRect(
                            color = segBg,
                            topLeft = Offset(sX, audioTop + 2f),
                            size = Size(segW, trackH - 4f)
                        )

                        // Split marker line
                        if (idx > 0) {
                            drawLine(
                                color = Color(0xFFFF9E00),
                                start = Offset(sX, audioTop + 2f),
                                end = Offset(sX, audioBottom - 2f),
                                strokeWidth = 2.5f
                            )
                        }
                    }

                    // Draw actual PCM waveform bars across source audio duration
                    val peaks = edl.waveformPeaks
                    val barCount = if (peaks.isNotEmpty()) peaks.size else 64
                    val slotW = w / barCount
                    for (i in 0 until barCount) {
                        val amp = if (peaks.isNotEmpty()) peaks[i] else 0.25f
                        val barSourceSec = ((i + 0.5f) / barCount) * totalSourceDur
                        val inTrimRange = barSourceSec in edl.trimStartSec..edl.trimEndSec
                        val inRemovedSeg = edl.audioSegments.any {
                            it.isRemoved && barSourceSec >= it.sourceStartSec && barSourceSec <= it.sourceEndSec
                        }
                        val barH = (amp * (trackH - 10f)).coerceAtLeast(3f)
                        val barColor = when {
                            !inTrimRange || inRemovedSeg -> Color(0x5577748F)
                            else -> Color(0xFF00E5FF)
                        }
                        drawLine(
                            color = barColor,
                            start = Offset(i * slotW + slotW * 0.5f, audioMidY - barH * 0.5f),
                            end = Offset(i * slotW + slotW * 0.5f, audioMidY + barH * 0.5f),
                            strokeWidth = (slotW * 0.55f).coerceAtLeast(1.5f)
                        )
                    }

                    // Shade trimmed-out start/end regions on the audio track
                    if (edl.trimStartSec > 0.05f) {
                        val trimLeftW = (edl.trimStartSec / totalSourceDur) * w
                        drawRect(
                            color = Color(0xAA090812),
                            topLeft = Offset(0f, audioTop),
                            size = Size(trimLeftW, trackH)
                        )
                    }
                    if (edl.trimEndSec < totalSourceDur - 0.05f) {
                        val trimRightX = (edl.trimEndSec / totalSourceDur) * w
                        drawRect(
                            color = Color(0xAA090812),
                            topLeft = Offset(trimRightX, audioTop),
                            size = Size(w - trimRightX, trackH)
                        )
                    }

                    // TRACK 3: LYRICS / CAPTION TRACK
                    val lyricTop = trackH * 2f
                    edl.lyrics.forEach { clip ->
                        val lxStart = (clip.startSec / totalEditedDur) * w
                        val lxEnd = (clip.endSec / totalEditedDur) * w
                        val lw = (lxEnd - lxStart - 2f).coerceAtLeast(6f)
                        val isSelected = clip.id == selectedLyricId

                        val lyricColor = when {
                            isSelected -> Color(0xFFA8F240)
                            clip.isUserCorrected -> Color(0xFF1EE89A)
                            clip.isChorusHighlight -> Color(0xFF9E6BFF)
                            else -> Color(0xFF3E356B)
                        }
                        drawRoundRect(
                            color = lyricColor.copy(alpha = if (isSelected) 0.9f else 0.65f),
                            topLeft = Offset(lxStart + 1f, lyricTop + 6f),
                            size = Size(lw, trackH - 12f),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }

                    // PLAYHEAD NEEDLE ACROSS ALL 3 TRACKS
                    val playheadX = ((playheadSec / totalEditedDur).coerceIn(0f, 1f)) * w
                    drawLine(
                        color = Color(0xFFA8F240),
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, h),
                        strokeWidth = 3f
                    )
                    drawCircle(
                        color = Color(0xFFA8F240),
                        radius = 6f,
                        center = Offset(playheadX, 6f)
                    )
                }
            }

            // Legend row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Track 1: Video Scenes & ◆ Transitions",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricViolet
                )
                Text(
                    text = "Track 2: PCM Audio & ✂ Splits",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
                Text(
                    text = "Track 3: Synced Lyrics",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberLime
                )
            }
        }
    }
}

// ============================================================================
// TAB 1: VIDEO SCENE EDITING PANEL
// ============================================================================

@Composable
private fun VideoSceneEditorPanel(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel,
    context: Context
) {
    val edl = uiState.projectEdl
    val selectedScene = uiState.selectedSceneClip ?: return
    val selectedIndex = edl.scenes.indexOfFirst { it.id == selectedScene.id }.coerceAtLeast(0)

    var editTitle by remember(selectedScene.id, selectedScene.activeVersionIndex, edl.revisionNumber) {
        mutableStateOf(selectedScene.sceneTitle)
    }
    var editSectionType by remember(selectedScene.id, selectedScene.activeVersionIndex, edl.revisionNumber) {
        mutableStateOf(selectedScene.sectionType)
    }
    val localMp4PickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onSceneLocalMp4Picked(context, uri)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = StudioCardDark
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Scene Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                edl.scenes.forEachIndexed { index, scene ->
                    val isSelected = scene.id == selectedScene.id
                    val hasVerifiedAsset = !scene.assetId.isNullOrBlank()
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectScene(scene.id) },
                        label = {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "S%d%s: %s (%.1fs–%.1fs)",
                                    index + 1,
                                    if (hasVerifiedAsset) " [MP4 ASSET]" else "",
                                    scene.sceneTitle.take(16),
                                    scene.startSec,
                                    scene.endSec
                                )
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (hasVerifiedAsset) SuccessEmerald else ElectricViolet,
                            selectedLabelColor = if (hasVerifiedAsset) Color.Black else TextPrimaryWhite
                        ),
                        modifier = Modifier.testTag("scene_select_chip_$index")
                    )
                }
            }

            // Reorder, Add, Delete & Single-Scene Regenerate Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { viewModel.reorderSelectedScene(-1) },
                    enabled = selectedIndex > 0,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("scene_move_up_button")
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Scene Earlier", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Move Earlier")
                }

                OutlinedButton(
                    onClick = { viewModel.reorderSelectedScene(1) },
                    enabled = selectedIndex < edl.scenes.lastIndex,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("scene_move_down_button")
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Scene Later", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Move Later")
                }

                OutlinedButton(
                    onClick = { viewModel.addSceneAfterSelected() },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("scene_add_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Scene", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Scene")
                }

                OutlinedButton(
                    onClick = { viewModel.deleteSelectedScene() },
                    enabled = edl.scenes.size > 1,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("scene_delete_button")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Scene",
                        tint = if (edl.scenes.size > 1) ErrorCoral else TextSecondaryMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }

            // Single-Scene Regenerate & Version History Restore
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = StudioCardElevated
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = CyberLime,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Scene Versions (${selectedScene.versions.size} saved)",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimaryWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { viewModel.regenerateSelectedScene() },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                            modifier = Modifier.testTag("scene_regenerate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = "Regenerate Selected Scene Only",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Regenerate Scene Only")
                        }
                    }

                    // Version Selector Chips to restore any previous version of this scene
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedScene.versions.forEachIndexed { vIdx, ver ->
                            FilterChip(
                                selected = vIdx == selectedScene.activeVersionIndex,
                                onClick = { viewModel.restoreSelectedSceneVersion(vIdx) },
                                label = { Text(ver.label) },
                                modifier = Modifier.testTag("scene_version_chip_$vIdx")
                            )
                        }
                    }
                }
            }

            // Provider-Independent Scene MP4 Asset Ingestion (Phase 2B)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = StudioCardElevated
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VideoStable,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Verified Scene MP4 Asset Ingestion",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val sourceBadgeText = if (!selectedScene.assetId.isNullOrBlank()) {
                            "ASSET: ${selectedScene.assetId}"
                        } else {
                            "ACTIVE: PHOTO MOTION"
                        }
                        Text(
                            text = sourceBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!selectedScene.assetId.isNullOrBlank()) CyberLime else TextSecondaryMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            localMp4PickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        enabled = !uiState.isIngestingSceneAsset,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scene_import_local_mp4_button")
                    ) {
                        if (uiState.isIngestingSceneAsset) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CyberLime
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Import & Verify Local MP4 Asset",
                                tint = CyberLime,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isIngestingSceneAsset) "Validating & Promoting MP4…" else "Import Local MP4 Asset",
                            color = CyberLime,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "Stages selected MP4 in .staging_tmp/, verifies streaming SHA-256, validates H.264/HEVC video track & VFR PTS cadence, atomically promotes to studio_assets/, and commits an immutable Room manifest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryMuted
                    )

                    if (!uiState.assetIngestionStatusMessage.isNullOrBlank()) {
                        Text(
                            text = uiState.assetIngestionStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberLime
                        )
                    }
                    if (!uiState.assetIngestionErrorMessage.isNullOrBlank()) {
                        Text(
                            text = uiState.assetIngestionErrorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorCoral
                        )
                    }
                }
            }

            // Scene Duration Editor
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Scene Duration: %.1fs (%.1fs – %.1fs)",
                            selectedScene.durationSec,
                            selectedScene.startSec,
                            selectedScene.endSec
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimaryWhite
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.changeSelectedSceneDuration(selectedScene.durationSec - 0.5f) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("scene_duration_minus_button")
                        ) {
                            Text("-0.5s")
                        }
                        OutlinedButton(
                            onClick = { viewModel.changeSelectedSceneDuration(selectedScene.durationSec + 0.5f) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("scene_duration_plus_button")
                        ) {
                            Text("+0.5s")
                        }
                    }
                }

                val maxSceneDur = (edl.totalEditedDurationSec - (edl.scenes.size - 1) * 0.5f).coerceAtLeast(1.5f)
                Slider(
                    value = selectedScene.durationSec.coerceIn(0.5f, maxSceneDur),
                    onValueChange = { viewModel.changeSelectedSceneDuration(it) },
                    valueRange = 0.5f..maxSceneDur,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberLime,
                        activeTrackColor = ElectricViolet
                    ),
                    modifier = Modifier.testTag("scene_duration_slider")
                )
            }

            // Camera Movement Selector for Selected Scene
            Text(
                text = "Scene Camera Movement:",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondaryMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraMovementPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedScene.cameraPreset == preset,
                        onClick = { viewModel.updateSelectedSceneCamera(preset) },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("scene_camera_chip_${preset.name.lowercase()}")
                    )
                }
            }

            // Transition Selector for Selected Scene
            Text(
                text = "Scene Boundary Transition:",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondaryMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EdlTransitionType.entries.forEach { trans ->
                    FilterChip(
                        selected = selectedScene.transitionType == trans,
                        onClick = { viewModel.updateSelectedSceneTransition(trans) },
                        label = { Text(trans.label) },
                        modifier = Modifier.testTag("scene_transition_chip_${trans.name.lowercase()}")
                    )
                }
            }

            // Replace / Customize Scene Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text("Scene Title") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scene_title_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimaryWhite,
                        unfocusedTextColor = TextPrimaryWhite,
                        focusedBorderColor = CyberLime,
                        unfocusedBorderColor = StudioBorderSubtle
                    )
                )

                Button(
                    onClick = {
                        viewModel.replaceSelectedScene(
                            newTitle = editTitle,
                            newSectionType = editSectionType,
                            newCameraPreset = selectedScene.cameraPreset,
                            newTransition = selectedScene.transitionType,
                            newLightingIntensity = selectedScene.lightingIntensity,
                            newCameraNote = "${selectedScene.cameraPreset.label} • ${selectedScene.transitionType.label}"
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberLime,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.testTag("scene_replace_apply_button")
                ) {
                    Text("Replace Scene", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================================================================
// TAB 2: LYRIC / CAPTION TRACK EDITING PANEL
// ============================================================================

@Composable
private fun LyricTrackEditorPanel(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel
) {
    val edl = uiState.projectEdl
    val selectedLyric = uiState.selectedLyricClip

    var lyricTextDraft by remember(selectedLyric?.id, edl.revisionNumber) {
        mutableStateOf(selectedLyric?.text.orEmpty())
    }
    var isChorusDraft by remember(selectedLyric?.id, edl.revisionNumber) {
        mutableStateOf(selectedLyric?.isChorusHighlight ?: false)
    }

    LaunchedEffect(selectedLyric?.id, selectedLyric?.text) {
        if (selectedLyric != null) {
            lyricTextDraft = selectedLyric.text
            isChorusDraft = selectedLyric.isChorusHighlight
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = StudioCardDark
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Lyrics Track Enabled",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimaryWhite
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = uiState.lyricsEnabled,
                        onCheckedChange = { viewModel.toggleLyrics(it) }
                    )
                }

                Button(
                    onClick = {
                        viewModel.addUserCorrectedLyricAtPlayhead(
                            text = lyricTextDraft.ifBlank { "User-Corrected Lyric Line" },
                            isChorusHighlight = isChorusDraft
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                    modifier = Modifier.testTag("lyric_add_at_playhead_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        String.format(
                            Locale.US,
                            "Add Lyric @ %.1fs",
                            uiState.previewTimeSec
                        )
                    )
                }
            }

            if (edl.lyrics.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = StudioCardElevated
                ) {
                    Text(
                        text = "No lyric captions on timeline yet. Tap 'Add Lyric' above to insert a user-verified lyric at the current playhead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryMuted,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                // Lyric Clip Selector Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    edl.lyrics.forEachIndexed { idx, clip ->
                        FilterChip(
                            selected = clip.id == selectedLyric?.id,
                            onClick = { viewModel.selectLyric(clip.id) },
                            label = {
                                Text(
                                    String.format(
                                        Locale.US,
                                        "#%d (%.1fs–%.1fs) %s",
                                        idx + 1,
                                        clip.startSec,
                                        clip.endSec,
                                        clip.text.take(18)
                                    )
                                )
                            },
                            modifier = Modifier.testTag("lyric_select_chip_$idx")
                        )
                    }
                }

                if (selectedLyric != null) {
                    Text(
                        text = "Provenance: ${selectedLyric.sourceType.badgeLabel} • ${selectedLyric.uncertaintyNote}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )

                    OutlinedTextField(
                        value = lyricTextDraft,
                        onValueChange = { lyricTextDraft = it },
                        label = { Text("Edit / Correct Transcription Text") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lyric_text_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimaryWhite,
                            unfocusedTextColor = TextPrimaryWhite,
                            focusedBorderColor = CyberLime,
                            unfocusedBorderColor = StudioBorderSubtle
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = isChorusDraft,
                            onClick = { isChorusDraft = !isChorusDraft },
                            label = { Text("★ Chorus Highlight Style") }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.deleteSelectedLyric() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("lyric_delete_button")
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove Caption",
                                    tint = ErrorCoral,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove")
                            }

                            Button(
                                onClick = {
                                    viewModel.updateSelectedLyricText(lyricTextDraft, isChorusDraft)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberLime,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("lyric_save_text_button")
                            ) {
                                Text("Save Correction", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Start / End Timestamp Fine-Tuning & Timeline Nudge Controls
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = StudioCardElevated
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Caption Timing Window: %.2fs – %.2fs (Duration %.2fs)",
                                    selectedLyric.startSec,
                                    selectedLyric.endSec,
                                    selectedLyric.durationSec
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimaryWhite,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.nudgeSelectedLyricOnTimeline(-0.5f) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("lyric_nudge_left_button")
                                ) {
                                    Text("<< Move -0.5s")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.nudgeSelectedLyricOnTimeline(0.5f) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("lyric_nudge_right_button")
                                ) {
                                    Text("Move +0.5s >>")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.adjustSelectedLyricTimestamps(
                                            selectedLyric.startSec - 0.25f,
                                            selectedLyric.endSec
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("lyric_start_minus_button")
                                ) {
                                    Text("Start -0.25s")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.adjustSelectedLyricTimestamps(
                                            selectedLyric.startSec + 0.25f,
                                            selectedLyric.endSec
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("lyric_start_plus_button")
                                ) {
                                    Text("Start +0.25s")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.adjustSelectedLyricTimestamps(
                                            selectedLyric.startSec,
                                            selectedLyric.endSec - 0.25f
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("lyric_end_minus_button")
                                ) {
                                    Text("End -0.25s")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.adjustSelectedLyricTimestamps(
                                            selectedLyric.startSec,
                                            selectedLyric.endSec + 0.25f
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("lyric_end_plus_button")
                                ) {
                                    Text("End +0.25s")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: NONDESTRUCTIVE AUDIO EDITING PANEL
// ============================================================================

@Composable
private fun AudioTrackEditorPanel(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel,
    context: Context
) {
    val edl = uiState.projectEdl
    val maxSourceDur = edl.sourceDurationSec.coerceAtLeast(2f)
    val currentSourceTime = EdlTimelineEngine.editedTimeToSourceTime(edl, uiState.previewTimeSec)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = StudioCardDark
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Nondestructive Audio Track Editor",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimaryWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Original MP3 file is never modified. Trims & removed splits apply to both Preview and HD MP4 Export.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryMuted
                    )
                }

                Button(
                    onClick = { viewModel.splitAudioAtPlayhead() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                    modifier = Modifier.testTag("audio_split_at_playhead_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = "Split Audio at Playhead",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(String.format(Locale.US, "Split @ %.1fs", currentSourceTime))
                }
            }

            // Trim Beginning & Ending Sliders
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = StudioCardElevated
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Trim Beginning: %.1fs  •  Trim Ending: %.1fs (Source %.1fs)",
                            edl.trimStartSec,
                            edl.trimEndSec,
                            maxSourceDur
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberLime,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Trim Start (${String.format(Locale.US, "%.1fs", edl.trimStartSec)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryMuted
                    )
                    Slider(
                        value = edl.trimStartSec,
                        onValueChange = { newStart ->
                            viewModel.trimEdlAudioRange(newStart, edl.trimEndSec)
                        },
                        valueRange = 0f..(edl.trimEndSec - 1f).coerceAtLeast(0.5f),
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricViolet
                        ),
                        modifier = Modifier.testTag("audio_trim_start_slider")
                    )

                    Text(
                        text = "Trim End (${String.format(Locale.US, "%.1fs", edl.trimEndSec)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryMuted
                    )
                    Slider(
                        value = edl.trimEndSec,
                        onValueChange = { newEnd ->
                            viewModel.trimEdlAudioRange(edl.trimStartSec, newEnd)
                        },
                        valueRange = (edl.trimStartSec + 1f).coerceAtMost(maxSourceDur)..maxSourceDur,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberLime,
                            activeTrackColor = ElectricViolet
                        ),
                        modifier = Modifier.testTag("audio_trim_end_slider")
                    )
                }
            }

            // Split Audio Segments List (Select, Preview, Remove / Restore Segment)
            Text(
                text = "Audio Segments (${edl.activeAudioSegments.size} Active / ${edl.audioSegments.size} Total):",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimaryWhite,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                edl.audioSegments.forEachIndexed { idx, seg ->
                    val isSelected = seg.id == uiState.selectedAudioSegment?.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.selectAudioSegment(seg.id) }
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = when {
                                    seg.isRemoved -> ErrorCoral
                                    isSelected -> CyberLime
                                    else -> StudioBorderSubtle
                                },
                                shape = RoundedCornerShape(12.dp)
                            )
                            .testTag("audio_segment_card_$idx"),
                        color = if (seg.isRemoved) StudioSurfaceDark else StudioCardElevated
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Segment #%d: %.2fs – %.2fs (%.2fs)",
                                        idx + 1,
                                        seg.sourceStartSec,
                                        seg.sourceEndSec,
                                        seg.sourceDurationSec
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (seg.isRemoved) TextSecondaryMuted else TextPrimaryWhite,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (seg.isRemoved) {
                                        "REMOVED (Skipped in Preview & Export)"
                                    } else {
                                        "ACTIVE IN TIMELINE"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (seg.isRemoved) ErrorCoral else SuccessEmerald
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!seg.isRemoved) {
                                    IconButton(
                                        onClick = {
                                            viewModel.selectAudioSegment(seg.id, jumpPlayhead = true)
                                            viewModel.previewSelectedAudioSegment(context)
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(ElectricIndigo)
                                            .testTag("audio_preview_segment_button_$idx")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Preview Segment",
                                            tint = CyberLime,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { viewModel.toggleRemoveAudioSegmentById(seg.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("audio_toggle_remove_segment_button_$idx")
                                ) {
                                    Text(
                                        text = if (seg.isRemoved) "Restore" else "Remove",
                                        color = if (seg.isRemoved) CyberLime else ErrorCoral
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
