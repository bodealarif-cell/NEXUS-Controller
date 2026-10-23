package com.nexus.controller.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0A101C)
val Charcoal = Color(0xFF121826)
val Panel = Color(0xFF1A2236)
val Accent = Color(0xFF22D3EE)
val AccentDim = Color(0xFF0E7490)
val Danger = Color(0xFFF87171)
val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val TextMain = Color(0xFFE6EDF7)
val Muted = Color(0xFF8B98B0)

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Navy, background = Navy, onBackground = TextMain,
            surface = Panel, onSurface = TextMain, surfaceVariant = Charcoal, onSurfaceVariant = Muted, error = Danger
        ),
        content = content
    )
}
