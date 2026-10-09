package com.diagramm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DE6B0),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFF7FA8FF),
    background = Color(0xFF1B2540),
    onBackground = Color(0xFFE6EBF7),
    surface = Color(0xFF222E4D),
    onSurface = Color(0xFFE6EBF7),
    surfaceVariant = Color(0xFF2B3A5E),
    onSurfaceVariant = Color(0xFFA3AFCB),
    surfaceContainer = Color(0xFF222E4D),
    surfaceContainerHigh = Color(0xFF2B3A5E),
    outline = Color(0xFF4A5A82),
    error = Color(0xFFFF5A72),
    onError = Color(0xFF3B0010),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00855F),
    onPrimary = Color.White,
    secondary = Color(0xFF3F6FD8),
    background = Color(0xFFF3F5FB),
    onBackground = Color(0xFF1B2540),
    surface = Color.White,
    onSurface = Color(0xFF1B2540),
    surfaceVariant = Color(0xFFE3E8F4),
    onSurfaceVariant = Color(0xFF5A6788),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE9EDF7),
    outline = Color(0xFFB2BCD6),
    error = Color(0xFFD6334D),
    onError = Color.White,
)

@Composable
fun DiagrammTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
