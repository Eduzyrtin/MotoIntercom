package com.sanabria.motointercom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF9A2E),
    onPrimary = Color(0xFF2B1600),
    primaryContainer = Color(0xFF5C3300),
    onPrimaryContainer = Color(0xFFFFDCBE),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA35100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF331A00),
)

@Composable
fun MotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
