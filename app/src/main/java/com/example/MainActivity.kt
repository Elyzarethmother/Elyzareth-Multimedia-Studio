package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.StudioCreateScreen
import com.example.ui.StudioExportsScreen
import com.example.ui.StudioPreviewScreen
import com.example.ui.theme.CyberLime
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StudioCardElevated
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurfaceDark
import com.example.ui.theme.TextPrimaryWhite
import com.example.ui.theme.TextSecondaryMuted
import com.example.viewmodel.MusicVideoViewModel
import com.example.viewmodel.StudioDestination

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MusicVideoStudioApp()
            }
        }
    }
}

@Composable
fun MusicVideoStudioApp(
    viewModel: MusicVideoViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.initializeDefaultAssets(context)
    }

    LaunchedEffect(uiState.statusSnackbarMessage) {
        val msg = uiState.statusSnackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = StudioObsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            StudioTopHeaderBar(
                activeResolution = uiState.selectedResolution.label.replace("Resolution ", ""),
                activeAspectRatio = uiState.selectedAspectRatio.label.replace("Aspect ratio ", "")
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = StudioSurfaceDark,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = uiState.currentTab == StudioDestination.CREATE,
                    onClick = { viewModel.selectTab(StudioDestination.CREATE, context) },
                    icon = {
                        Icon(
                            imageVector = if (uiState.currentTab == StudioDestination.CREATE) {
                                Icons.Filled.MovieFilter
                            } else {
                                Icons.Outlined.MovieFilter
                            },
                            contentDescription = stringResource(R.string.tab_create)
                        )
                    },
                    label = { Text(stringResource(R.string.tab_create)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberLime,
                        selectedTextColor = TextPrimaryWhite,
                        indicatorColor = ElectricIndigo,
                        unselectedIconColor = TextSecondaryMuted,
                        unselectedTextColor = TextSecondaryMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_create")
                )

                NavigationBarItem(
                    selected = uiState.currentTab == StudioDestination.PREVIEW,
                    onClick = { viewModel.selectTab(StudioDestination.PREVIEW, context) },
                    icon = {
                        Icon(
                            imageVector = if (uiState.currentTab == StudioDestination.PREVIEW) {
                                Icons.Filled.PlayCircleFilled
                            } else {
                                Icons.Outlined.PlayCircleOutline
                            },
                            contentDescription = stringResource(R.string.tab_preview)
                        )
                    },
                    label = { Text(stringResource(R.string.tab_preview)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberLime,
                        selectedTextColor = TextPrimaryWhite,
                        indicatorColor = ElectricIndigo,
                        unselectedIconColor = TextSecondaryMuted,
                        unselectedTextColor = TextSecondaryMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_preview")
                )

                NavigationBarItem(
                    selected = uiState.currentTab == StudioDestination.EXPORTS,
                    onClick = { viewModel.selectTab(StudioDestination.EXPORTS, context) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.exportedVideos.isNotEmpty()) {
                                    Badge(
                                        containerColor = CyberLime,
                                        contentColor = StudioObsidian
                                    ) {
                                        Text(uiState.exportedVideos.size.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (uiState.currentTab == StudioDestination.EXPORTS) {
                                    Icons.Filled.HighQuality
                                } else {
                                    Icons.Outlined.HighQuality
                                },
                                contentDescription = stringResource(R.string.tab_exports)
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.tab_exports)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberLime,
                        selectedTextColor = TextPrimaryWhite,
                        indicatorColor = ElectricIndigo,
                        unselectedIconColor = TextSecondaryMuted,
                        unselectedTextColor = TextSecondaryMuted
                    ),
                    modifier = Modifier.testTag("nav_tab_exports")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.currentTab) {
                StudioDestination.CREATE -> StudioCreateScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
                StudioDestination.PREVIEW -> StudioPreviewScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
                StudioDestination.EXPORTS -> StudioExportsScreen(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun StudioTopHeaderBar(
    activeResolution: String,
    activeAspectRatio: String
) {
    Surface(
        color = StudioObsidian,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(ElectricViolet, CyberLime)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoStable,
                        contentDescription = null,
                        tint = StudioObsidian,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryWhite
                    )
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondaryMuted
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StudioCardElevated
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = activeResolution,
                        style = MaterialTheme.typography.labelLarge,
                        color = CyberLime
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondaryMuted
                    )
                    Text(
                        text = activeAspectRatio,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimaryWhite
                    )
                }
            }
        }
    }
}
