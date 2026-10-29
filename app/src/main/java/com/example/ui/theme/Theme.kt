package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RiderCamColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = CockpitCard,
    onPrimaryContainer = NeonGreen,
    secondary = NeonCyan,
    onSecondary = Color.Black,
    tertiary = NeonYellow,
    background = CockpitBlack,
    onBackground = HudWhite,
    surface = CockpitSurface,
    onSurface = HudWhite,
    surfaceVariant = CockpitCard,
    onSurfaceVariant = HudMuted,
    outline = CockpitBorder
)

@Composable
fun RiderCamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RiderCamColorScheme,
        typography = Typography,
        content = content
    )
}
