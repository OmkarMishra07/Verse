package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = iPodAccentRed,
    onPrimary = Color.White,
    secondary = iPodChassis,
    onSecondary = Color.White,
    background = iPodDisplayBg,
    onBackground = Color.White,
    surface = iPodClickWheel,
    onSurface = Color.White,
    surfaceVariant = iPodCenterSelect,
    onSurfaceVariant = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark mode for premium iPod OLED aesthetic
    dynamicColor: Boolean = false, // Do not use Android dynamic colors to maintain Apple industrial palette
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
