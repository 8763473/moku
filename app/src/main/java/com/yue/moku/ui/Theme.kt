package com.yue.moku.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1ECE2),
    onPrimaryContainer = Color(0xFF153C31),
    secondary = Color(0xFF79614B),
    secondaryContainer = Color(0xFFF1E2D3),
    background = Color(0xFFF7F5F0),
    surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFECEAE4),
    outline = Color(0xFF777A74),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6D5C4),
    onPrimary = Color(0xFF07382C),
    primaryContainer = Color(0xFF245343),
    secondary = Color(0xFFD8C1A9),
    background = Color(0xFF111411),
    surface = Color(0xFF191C19),
    surfaceVariant = Color(0xFF272B27),
)

@Composable
fun MoKuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

