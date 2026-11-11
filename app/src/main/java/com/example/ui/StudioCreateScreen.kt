package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AudioSource
import com.example.model.CameraMovementPreset
import com.example.model.StudioSamplePresets
import com.example.model.VideoAspectRatio
import com.example.model.VideoModelType
import com.example.model.VideoResolution
import com.example.model.VisualFilterStyle
import com.example.ui.theme.CyberLime
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonPurpleGlow
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCardDark
import com.example.ui.theme.StudioCardElevated
import com.example.ui.theme.StudioSurfaceDark
import com.example.ui.theme.TextHintSlate
import com.example.ui.theme.TextPrimaryWhite
import com.example.ui.theme.TextSecondaryMuted
import com.example.ui.theme.WarningAmber
import com.example.viewmodel.MusicVideoViewModel
import com.example.viewmodel.StudioUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioCreateScreen(
    uiState: StudioUiState,
    viewModel: MusicVideoViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Zero-permission Android Photo Picker per Google Play Policy
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onPhotoPicked(context, uri)
        }
    }

    // Standard zero-permission Storage Access Framework picker for MP3 / Audio files
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onAudioMp3Picked(context, uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Studio Composer Container matching the uploaded reference screenshot
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            ElectricViolet.copy(alpha = 0.65f),
                            StudioBorderSubtle,
                            CyberLime.copy(alpha = 0.35f)
                        )
                    ),
                    shape = RoundedCornerShape(26.dp)
                ),
            shape = RoundedCornerShape(26.dp),
            color = StudioSurfaceDark.copy(alpha = 0.96f),
            tonalElevation = 8.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ElectricViolet.copy(alpha = 0.22f),
                                CyberLime.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val isWideLayout = maxWidth >= 560.dp

                        if (isWideLayout) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                AddPhotoCard(
                                    uiState = uiState,
                                    onPickPhoto = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(0.38f)
                                        .height(238.dp)
                                )
                                Column(
                                    modifier = Modifier.weight(0.62f),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    SelectSongRow(
                                        uiState = uiState,
                                        onSelectMp3 = { audioPickerLauncher.launch("audio/*") }
                                    )
                                    VideoPromptBox(
                                        promptText = uiState.promptText,
                                        onPromptChange = viewModel::onPromptChanged,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(166.dp)
                                    )
                                }
                            }
                        } else {
                            // Compact Portrait Layout keeping exact visual DNA of the screenshot
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                AddPhotoCard(
                                    uiState = uiState,
                                    onPickPhoto = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(196.dp)
                                )
                                SelectSongRow(
                                    uiState = uiState,
                                    onSelectMp3 = { audioPickerLauncher.launch("audio/*") }
                                )
                                VideoPromptBox(
                                    promptText = uiState.promptText,
                                    onPromptChange = viewModel::onPromptChanged,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(152.dp)
                                )
                            }
                        }
                    }

                    // Bottom Control Bar from the screenshot:
                    // Model Standard v | Resolution 720P v | Aspect ratio 9 : 16 v | More Options | Lyrics [toggle]
                    StudioBottomConfigBar(
                        uiState = uiState,
                        onSelectModel = viewModel::selectModel,
                        onSelectResolution = viewModel::selectResolution,
                        onSelectAspectRatio = viewModel::selectAspectRatio,
                        onOpenMoreOptions = { viewModel.setShowMoreOptions(true) },
                        onToggleLyrics = viewModel::toggleLyrics
                    )
                }
            }
        }

        // Primary Action Buttons: AI Director Enhance + Generate & Preview + Direct HD Export
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { viewModel.runAiStoryboardAnalysis(context) },
                modifier = Modifier
                    .weight(0.42f)
                    .height(54.dp)
                    .testTag("ai_storyboard_button"),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonPurpleGlow)
            ) {
                if (uiState.isGeneratingStoryboard) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CyberLime
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = stringResource(R.string.ai_storyboard_button),
                        tint = CyberLime,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Build Scene Cues",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimaryWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { viewModel.generateAndOpenPreview(context) },
                modifier = Modifier
                    .weight(0.58f)
                    .height(54.dp)
                    .testTag("generate_preview_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricViolet,
                    contentColor = TextPrimaryWhite
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.generate_preview_button),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Preview & Export HD",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Transparent AI Error Reporting Banner (Network, Authentication/App Check, or Quota)
        uiState.aiErrorMessage?.let { errorMsg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
                    .testTag("ai_error_banner"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Storyboard Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Local Storyboard Diagnostic",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimaryWhite
                            )
                        }
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelLarge,
                            color = CyberLime,
                            modifier = Modifier
                                .clickable { viewModel.runAiStoryboardAnalysis(context) }
                                .padding(4.dp)
                        )
                    }
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimaryWhite
                    )
                    Text(
                        text = "Note: Local audio playback, video preview, and HD MP4 export remain 100% functional offline.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondaryMuted
                    )
                }
            }
        }

        // Quick Studio Presets (Try 1-Tap Sample MP3 + Photo + Prompt combinations)
        StudioPresetsSection(
            uiState = uiState,
            onSelectPreset = { preset -> viewModel.selectPresetTrack(context, preset) },
            onSelectSamplePhoto = { resId, title -> viewModel.selectSamplePhoto(context, resId, title) }
        )

        // Local PCM Spectrum Storyboard Summary Card
        uiState.storyboardPlan?.let { plan ->
            AiStoryboardSummaryCard(
                plan = plan
            )
        }
    }

    if (uiState.showMoreOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowMoreOptions(false) },
            containerColor = StudioSurfaceDark,
            contentColor = TextPrimaryWhite
        ) {
            MoreOptionsSheetContent(
                uiState = uiState,
                onSelectCamera = viewModel::selectCameraPreset,
                onSelectFilter = viewModel::selectFilterStyle,
                onToggleFullSong = viewModel::setExportFullSong,
                onChangeClipRange = viewModel::setClipRange,
                onClose = { viewModel.setShowMoreOptions(false) }
            )
        }
    }
}

@Composable
private fun AddPhotoCard(
    uiState: StudioUiState,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = uiState.loadedBitmap
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF4B3D77).copy(alpha = 0.65f),
                        Color(0xFF564497).copy(alpha = 0.75f),
                        Color(0xFF2B2845).copy(alpha = 0.85f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onPickPhoto)
            .testTag("add_photo_card"),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.add_photo),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Frosted overlay pill at bottom + center "+" icon matching reference
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x44100C24),
                                Color(0x88100C24),
                                Color(0xCC100C24)
                            )
                        )
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_photo),
                    tint = TextPrimaryWhite,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.add_photo),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimaryWhite.copy(alpha = 0.92f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            val currentPhotoLabel = when (val p = uiState.selectedPhoto) {
                is com.example.model.PhotoSource.DrawableRes -> p.title
                is com.example.model.PhotoSource.LocalUri -> p.title
            }
            Text(
                text = currentPhotoLabel,
                style = MaterialTheme.typography.labelMedium,
                color = CyberLime,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SelectSongRow(
    uiState: StudioUiState,
    onSelectMp3: () -> Unit
) {
    val songLabel = when (val src = uiState.selectedAudio) {
        is AudioSource.LocalMp3Uri -> src.displayName
        is AudioSource.BuiltInSynthTrack -> src.title
    }

    SelectSongButton(
        songLabel = songLabel,
        isMp3Active = true,
        onClick = onSelectMp3,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    )
}

@Composable
private fun SelectSongButton(
    songLabel: String,
    isMp3Active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF392B7A),
                        Color(0xFF473799)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = if (isMp3Active) NeonPurpleGlow else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag("select_song_button"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Upload,
                contentDescription = stringResource(R.string.select_a_song),
                tint = TextPrimaryWhite,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = stringResource(R.string.select_a_song),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = songLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberLime,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VideoPromptBox(
    promptText: String,
    onPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF231F36).copy(alpha = 0.92f),
                        Color(0xFF1F271E).copy(alpha = 0.78f),
                        Color(0xFF181526).copy(alpha = 0.95f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(14.dp)
    ) {
        if (promptText.isEmpty()) {
            Text(
                text = stringResource(R.string.prompt_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = TextHintSlate
            )
        }

        BasicTextField(
            value = promptText,
            onValueChange = onPromptChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = TextPrimaryWhite,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(CyberLime),
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp)
                .testTag("video_prompt_input")
        )

        Text(
            text = "${promptText.length}/2000",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondaryMuted,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun StudioBottomConfigBar(
    uiState: StudioUiState,
    onSelectModel: (VideoModelType) -> Unit,
    onSelectResolution: (VideoResolution) -> Unit,
    onSelectAspectRatio: (VideoAspectRatio) -> Unit,
    onOpenMoreOptions: () -> Unit,
    onToggleLyrics: (Boolean) -> Unit
) {
    var showModelMenu by remember { mutableStateOf(false) }
    var showResolutionMenu by remember { mutableStateOf(false) }
    var showAspectMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Model Dropdown
        Box {
            DropdownTriggerLabel(
                label = uiState.selectedModel.label,
                testTag = "dropdown_model",
                onClick = { showModelMenu = true }
            )
            DropdownMenu(
                expanded = showModelMenu,
                onDismissRequest = { showModelMenu = false },
                modifier = Modifier.background(StudioCardElevated)
            ) {
                VideoModelType.entries.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "${model.label} (${model.badge})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimaryWhite
                                )
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondaryMuted
                                )
                            }
                        },
                        onClick = {
                            onSelectModel(model)
                            showModelMenu = false
                        }
                    )
                }
            }
        }

        // 2. Resolution Dropdown (720P / 1080P HD)
        Box {
            DropdownTriggerLabel(
                label = uiState.selectedResolution.label,
                testTag = "dropdown_resolution",
                onClick = { showResolutionMenu = true }
            )
            DropdownMenu(
                expanded = showResolutionMenu,
                onDismissRequest = { showResolutionMenu = false },
                modifier = Modifier.background(StudioCardElevated)
            ) {
                VideoResolution.entries.forEach { res ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${res.label} (${res.shortWidth}p HD • ${res.bitrateMbps} Mbps)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimaryWhite
                            )
                        },
                        onClick = {
                            onSelectResolution(res)
                            showResolutionMenu = false
                        }
                    )
                }
            }
        }

        // 3. Aspect Ratio Dropdown (9:16, 16:9, 1:1)
        Box {
            DropdownTriggerLabel(
                label = uiState.selectedAspectRatio.label,
                testTag = "dropdown_aspect_ratio",
                onClick = { showAspectMenu = true }
            )
            DropdownMenu(
                expanded = showAspectMenu,
                onDismissRequest = { showAspectMenu = false },
                modifier = Modifier.background(StudioCardElevated)
            ) {
                VideoAspectRatio.entries.forEach { ratio ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = ratio.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimaryWhite
                            )
                        },
                        onClick = {
                            onSelectAspectRatio(ratio)
                            showAspectMenu = false
                        }
                    )
                }
            }
        }

        // 4. More Options Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenMoreOptions)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("more_options_button")
        ) {
            Text(
                text = stringResource(R.string.more_options),
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimaryWhite
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = stringResource(R.string.more_options),
                tint = TextPrimaryWhite,
                modifier = Modifier.size(18.dp)
            )
        }

        // 5. Lyrics Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.lyrics_label),
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimaryWhite
            )
            Switch(
                checked = uiState.lyricsEnabled,
                onCheckedChange = onToggleLyrics,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ElectricViolet,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = StudioCardElevated
                ),
                modifier = Modifier.testTag("lyrics_switch")
            )
        }
    }
}

@Composable
private fun DropdownTriggerLabel(
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimaryWhite
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = label,
            tint = TextPrimaryWhite,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun StudioPresetsSection(
    uiState: StudioUiState,
    onSelectPreset: (AudioSource.BuiltInSynthTrack) -> Unit,
    onSelectSamplePhoto: (Int, String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = StudioCardDark,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = CyberLime,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sample_tracks_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimaryWhite
                    )
                }
                Text(
                    text = "MP3 + Photo Ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StudioSamplePresets.presets.forEach { preset ->
                    val isSelected = (uiState.selectedAudio as? AudioSource.BuiltInSynthTrack)?.id == preset.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = {
                            Text(
                                text = "${preset.title} (${preset.bpm} BPM)",
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricIndigo,
                            selectedLabelColor = TextPrimaryWhite,
                            containerColor = StudioSurfaceDark,
                            labelColor = TextSecondaryMuted
                        ),
                        modifier = Modifier.testTag("preset_chip_${preset.id}")
                    )
                }
            }

            // Quick Sample Cover Art Switcher
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Sample Backdrops:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondaryMuted
                )
                FilterChip(
                    selected = (uiState.selectedPhoto as? com.example.model.PhotoSource.DrawableRes)?.resId ==
                        R.drawable.img_sample_cyberpunk_stage_1794279281846,
                    onClick = {
                        onSelectSamplePhoto(
                            R.drawable.img_sample_cyberpunk_stage_1794279281846,
                            "Cyberpunk Stage Backdrop"
                        )
                    },
                    label = { Text("Synthwave Concert Stage") }
                )
                FilterChip(
                    selected = (uiState.selectedPhoto as? com.example.model.PhotoSource.DrawableRes)?.resId ==
                        R.drawable.img_sample_neon_portrait_1794279292218,
                    onClick = {
                        onSelectSamplePhoto(
                            R.drawable.img_sample_neon_portrait_1794279292218,
                            "Neon DJ Portrait"
                        )
                    },
                    label = { Text("Neon DJ Portrait") }
                )
            }
        }
    }
}

@Composable
private fun AiStoryboardSummaryCard(
    plan: com.example.model.AiStoryboardPlan
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(20.dp))
            .testTag("live_ai_storyboard_card"),
        shape = RoundedCornerShape(20.dp),
        color = StudioCardDark
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MovieCreation,
                        contentDescription = null,
                        tint = NeonPurpleGlow,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Local Spectrum Storyboard (${plan.modelUsed})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimaryWhite
                    )
                }
                Text(
                    text = plan.suggestedCamera.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberLime
                )
            }

            Text(
                text = plan.directorSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryWhite
            )

            if (plan.instrumentalSectionsSummary.isNotBlank() || plan.chorusHighlightTiming.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioSurfaceDark)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (plan.instrumentalSectionsSummary.isNotBlank()) {
                        Text(
                            text = "Instrumentals: ${plan.instrumentalSectionsSummary}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondaryMuted
                        )
                    }
                    if (plan.chorusHighlightTiming.isNotBlank()) {
                        Text(
                            text = "Chorus Highlight: ${plan.chorusHighlightTiming}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberLime
                        )
                    }
                }
            }

            // Timeline Cues (Scene descriptions, camera movements, transitions, vocal section tags)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                plan.cues.forEach { cue ->
                    Column(
                        modifier = Modifier
                            .width(195.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(StudioSurfaceDark)
                            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = cue.timeRangeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberLime
                            )
                            Text(
                                text = cue.sectionType,
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan
                            )
                        }
                        Text(
                            text = cue.sceneTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimaryWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = cue.cameraNote,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondaryMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Transition: ${cue.visualTransition} • ${cue.vocalSectionTag}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonPurpleGlow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Multimodal Audio Intelligence Report Section
            plan.audioIntelligence?.let { intel ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StudioSurfaceDark)
                        .border(1.dp, ElectricViolet.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Local Audio Intelligence",
                            style = MaterialTheme.typography.titleSmall,
                            color = ElectricCyan
                        )
                        Text(
                            text = if (intel.isFromRealAudioBytes) "Analyzed MP3 Audio" else "Analyzed Synth/Metadata",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberLime
                        )
                    }
                    Text(
                        text = intel.detectedStructure,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimaryWhite
                    )
                    Text(
                        text = intel.vocalCharacteristics,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondaryMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreOptionsSheetContent(
    uiState: StudioUiState,
    onSelectCamera: (CameraMovementPreset) -> Unit,
    onSelectFilter: (VisualFilterStyle) -> Unit,
    onToggleFullSong: (Boolean) -> Unit,
    onChangeClipRange: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "More Studio Options",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimaryWhite
        )

        Text(
            text = "Camera Movement Style",
            style = MaterialTheme.typography.titleSmall,
            color = CyberLime
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraMovementPreset.entries.forEach { preset ->
                val selected = uiState.selectedCamera == preset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) ElectricIndigo.copy(alpha = 0.45f) else StudioCardDark)
                        .border(
                            1.dp,
                            if (selected) ElectricViolet else StudioBorderSubtle,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectCamera(preset) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimaryWhite
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondaryMuted
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = CyberLime
                        )
                    }
                }
            }
        }

        Text(
            text = "Color Grade & Chorus Filter",
            style = MaterialTheme.typography.titleSmall,
            color = CyberLime
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VisualFilterStyle.entries.forEach { style ->
                FilterChip(
                    selected = uiState.selectedFilter == style,
                    onClick = { onSelectFilter(style) },
                    label = { Text(style.label) }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Export Complete Song (${uiState.fullSongDurationSec.toInt()}s)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimaryWhite
                    )
                    Text(
                        text = if (uiState.exportFullSong) {
                            "Full track export active (${uiState.exportDurationSec}s @ 24 FPS)"
                        } else {
                            "Custom clip range: ${uiState.effectiveClipStartSec.toInt()}s – ${uiState.effectiveClipEndSec.toInt()}s (${uiState.exportDurationSec}s)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberLime
                    )
                }
                Switch(
                    checked = uiState.exportFullSong,
                    onCheckedChange = onToggleFullSong,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ElectricViolet
                    ),
                    modifier = Modifier.testTag("full_song_export_switch")
                )
            }

            if (!uiState.exportFullSong) {
                val totalDur = uiState.fullSongDurationSec.coerceAtLeast(2f)
                Text(
                    text = "Clip Start: ${uiState.effectiveClipStartSec.toInt()}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondaryMuted
                )
                Slider(
                    value = uiState.effectiveClipStartSec,
                    onValueChange = { newStart ->
                        onChangeClipRange(newStart, uiState.effectiveClipEndSec)
                    },
                    valueRange = 0f..(totalDur - 1f).coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = CyberLime,
                        activeTrackColor = ElectricViolet
                    )
                )
                Text(
                    text = "Clip End: ${uiState.effectiveClipEndSec.toInt()}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondaryMuted
                )
                Slider(
                    value = uiState.effectiveClipEndSec,
                    onValueChange = { newEnd ->
                        onChangeClipRange(uiState.effectiveClipStartSec, newEnd)
                    },
                    valueRange = 1f..totalDur,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberLime,
                        activeTrackColor = ElectricViolet
                    )
                )
            }
        }

        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
        ) {
            Text("Done", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
