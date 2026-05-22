package com.ragav.lockscreenplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColors = lightColorScheme(
    primary = Graphite,
    secondary = Walnut,
    background = Snow,
    surface = Snow,
    onPrimary = Snow,
    onSecondary = Snow,
    onBackground = Graphite,
    onSurface = Graphite
)

@Composable
fun LockscreenPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content
    )
}
