package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioDarkColorScheme = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = TextPrimaryWhite,
    primaryContainer = ElectricIndigo,
    onPrimaryContainer = TextPrimaryWhite,
    secondary = CyberLime,
    onSecondary = Color(0xFF0A0D04),
    secondaryContainer = StudioCardElevated,
    onSecondaryContainer = TextPrimaryWhite,
    tertiary = ElectricCyan,
    onTertiary = Color(0xFF001F24),
    background = StudioObsidian,
    onBackground = TextPrimaryWhite,
    surface = StudioSurfaceDark,
    onSurface = TextPrimaryWhite,
    surfaceVariant = StudioCardDark,
    onSurfaceVariant = TextSecondaryMuted,
    outline = StudioBorderSubtle,
    error = CoralPulse,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        content = content
    )
}
