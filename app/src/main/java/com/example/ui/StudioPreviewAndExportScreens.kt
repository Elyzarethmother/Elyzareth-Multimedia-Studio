package com.example.ui

import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.media.VideoFrameRenderer
import com.example.model.CameraMovementPreset
import com.example.model.ExportedVideoItem
import com.example.model.VideoAspectRatio
import com.example.ui.theme.CyberLime
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPurpleGlow
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCardDark
import com.example.ui.theme.StudioCardElevated
import com.example.ui.theme.StudioSurfaceDark
import com.example.ui.theme.SuccessEmerald
import com.example.ui.theme.TextPrimaryWhite
import com.example.ui.theme.TextSecondaryMuted
import com.example.viewmodel.MusicVideoViewModel
import com.example.viewmodel.StudioDestination
import com.example.viewmodel.StudioUiState
import java.util.Locale

@Composable
fun StudioPreviewScreen(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    BackHandler {
        viewModel.selectTab(StudioDestination.CREATE, context)
    }

    val ratioFloat = when (uiState.selectedAspectRatio) {
        VideoAspectRatio.RATIO_9_16 -> 9f / 16f
        VideoAspectRatio.RATIO_16_9 -> 16f / 9f
        VideoAspectRatio.RATIO_1_1 -> 1f
    }

    val (outW, outH) = uiState.selectedAspectRatio.calculateDimensions(uiState.selectedResolution)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Preview Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.selectTab(StudioDestination.CREATE, context) },
                    modifier = Modifier.testTag("back_to_create_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_to_editor),
                        tint = TextPrimaryWhite
                    )
                }
                Column {
                    Text(
                        text = uiState.activeSongTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimaryWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${uiState.selectedModel.label} • ${outW}×${outH} HD • ${uiState.selectedCamera.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberLime
                    )
                }
            }
        }

        // Live Real-Time Music Video Canvas Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 420.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(ratioFloat, matchHeightConstraintsFirst = ratioFloat <= 1f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(2.dp, ElectricViolet, RoundedCornerShape(22.dp))
                    .background(Color.Black)
                    .clickable { viewModel.togglePreviewPlayback(context) }
                    .testTag("video_preview_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val nativeCanvas: Canvas = drawContext.canvas.nativeCanvas
                    VideoFrameRenderer.renderFrame(
                        canvas = nativeCanvas,
                        width = size.width.toInt().coerceAtLeast(1),
                        height = size.height.toInt().coerceAtLeast(1),
                        sourceBitmap = uiState.loadedBitmap,
                        timeSec = uiState.previewTimeSec,
                        durationSec = uiState.projectEdl.totalEditedDurationSec.coerceAtLeast(1f),
                        modelType = uiState.selectedModel,
                        cameraPreset = uiState.selectedCamera,
                        filterStyle = uiState.selectedFilter,
                        songTitle = uiState.activeSongTitle,
                        promptText = uiState.promptText,
                        lyricsEnabled = uiState.lyricsEnabled,
                        lyrics = uiState.activeLyrics,
                        storyboardCues = uiState.storyboardPlan?.cues.orEmpty(),
                        spectrumTimeline = uiState.audioSpectrumTimeline,
                        projectEdl = uiState.projectEdl
                    )
                }

                // Floating resolution badge in top-right corner
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = StudioSurfaceDark.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "${uiState.selectedResolution.shortWidth}P HD",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberLime,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Playback Controls & Timeline Scrubber Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = StudioCardDark
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.togglePreviewPlayback(context) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ElectricViolet)
                            .testTag("preview_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlayingPreview) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlayingPreview) "Pause" else "Play",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { viewModel.seekPreview(0f) },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(StudioCardElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Restart Preview",
                            tint = TextPrimaryWhite
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        val maxEditedDur = uiState.projectEdl.totalEditedDurationSec.coerceAtLeast(1f)
                        Slider(
                            value = uiState.previewTimeSec.coerceIn(0f, maxEditedDur),
                            onValueChange = { viewModel.seekPreview(it) },
                            valueRange = 0f..maxEditedDur,
                            colors = SliderDefaults.colors(
                                thumbColor = CyberLime,
                                activeTrackColor = ElectricViolet,
                                inactiveTrackColor = StudioSurfaceDark
                            ),
                            modifier = Modifier.testTag("preview_timeline_slider")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatSeconds(uiState.previewTimeSec),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimaryWhite
                            )
                            Text(
                                text = if (uiState.audioSpectrumTimeline?.isFromDecodedPcm == true) {
                                    "PCM Audio Spectrum & EDL Synced"
                                } else {
                                    "Analyzing Audio Waveform…"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberLime
                            )
                            Text(
                                text = formatSeconds(maxEditedDur),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondaryMuted
                            )
                        }
                    }
                }

                // Live Camera Preset Quick Switcher during preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CameraMovementPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = (uiState.selectedSceneClip?.cameraPreset ?: uiState.selectedCamera) == preset,
                            onClick = { viewModel.selectCameraPreset(preset) },
                            label = { Text(preset.label) }
                        )
                    }
                }
            }
        }

        // Nondestructive Editorial Workstation (Timeline + Scene / Lyric / Audio Editors + Undo/Redo)
        EditorialWorkstationSection(
            uiState = uiState,
            viewModel = viewModel,
            context = context
        )

        // HD Video Export Progress or Primary Export Button
        if (uiState.isExportingVideo) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyberLime, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                color = StudioCardElevated
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Encoding Real HD MP4 Video…",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryWhite
                        )
                        Text(
                            text = "${(uiState.exportProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberLime
                        )
                    }
                    LinearProgressIndicator(
                        progress = { uiState.exportProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = CyberLime,
                        trackColor = StudioSurfaceDark
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.exportStatusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondaryMuted,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { viewModel.cancelExport() },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        } else {
            val exportScopeLabel = if (uiState.exportFullSong) {
                "Full Song ${uiState.exportDurationSec}s"
            } else {
                "Clip ${uiState.exportDurationSec}s"
            }
            Button(
                onClick = { viewModel.exportHdVideo(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag("export_hd_mp4_button"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberLime,
                    contentColor = Color(0xFF0A0D04)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.export_hd_video_button),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Export HD MP4 Video (${outW}×${outH} • $exportScopeLabel)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StudioExportsScreen(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    BackHandler {
        viewModel.selectTab(StudioDestination.CREATE, context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Exported HD Music Videos",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimaryWhite
                )
                Text(
                    text = "Encoded with H.264 + AAC into standard .mp4 files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryMuted
                )
            }
            Button(
                onClick = { viewModel.exportHdVideo(context) },
                enabled = !uiState.isExportingVideo,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                modifier = Modifier.testTag("quick_export_button")
            ) {
                Icon(
                    imageVector = Icons.Default.HighQuality,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Render New HD")
            }
        }

        if (uiState.isExportingVideo) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = StudioCardElevated
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = uiState.exportStatusText,
                        style = MaterialTheme.typography.titleSmall,
                        color = CyberLime
                    )
                    LinearProgressIndicator(
                        progress = { uiState.exportProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = CyberLime
                    )
                }
            }
        }

        if (uiState.exportedVideos.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = StudioCardDark
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = NeonPurpleGlow,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No HD MP4 Exports Yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryWhite
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Configure your MP3 or Music Link, add a photo & video prompt, preview your camera motion, and tap 'Export HD MP4 Video'.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryMuted
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = { viewModel.selectTab(StudioDestination.PREVIEW, context) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Go to Video Preview")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.exportedVideos, key = { it.id }) { item ->
                    ExportedVideoCard(
                        item = item,
                        onShare = { viewModel.shareExportedVideo(context, item) },
                        onPreviewAgain = { viewModel.selectTab(StudioDestination.PREVIEW, context) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportedVideoCard(
    item: ExportedVideoItem,
    onShare: () -> Unit,
    onPreviewAgain: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = StudioCardDark
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricIndigo),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoFile,
                            contentDescription = null,
                            tint = CyberLime
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.resolutionLabel} • Ratio ${item.aspectRatioLabel} • ${item.durationSec}s • ${item.fileSizeKb} KB",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberLime
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Exported",
                    tint = SuccessEmerald,
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = "File: ${item.filePath}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondaryMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.savedToGalleryUri != null) {
                Text(
                    text = "✓ Saved to device Movies/MusicVideoStudio gallery (${item.createdAtFormatted})",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPreviewAgain,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Replay in Studio")
                }

                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share_exported_video_${item.id}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.share_video),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.share_video))
                }
            }
        }
    }
}

private fun formatSeconds(sec: Float): String {
    val totalSec = sec.toInt().coerceAtLeast(0)
    val mins = totalSec / 60
    val remSec = totalSec % 60
    return String.format(Locale.US, "%d:%02d", mins, remSec)
}
