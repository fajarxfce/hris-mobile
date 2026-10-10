package dev.fajar.starter.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.fajar.starter.designsystem.platform.isSystemInReducedMotion
import io.github.composefluent.Colors
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.generateShades

@OptIn(ExperimentalFluentApi::class)
@Composable
fun AppTheme(reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = remember(dark) { Colors(generateShades(Color(0xFF0078D4)), darkMode = dark) }
    val systemReduced = isSystemInReducedMotion()
    val motion =
        remember(reduceMotion, systemReduced) {
            if (reduceMotion || systemReduced) AppMotion(0, 0, 0) else AppMotion()
        }
    FluentTheme(colors = colors, compactMode = false, useAcrylicPopup = false) {
        CompositionLocalProvider(
            LocalAppMotion provides motion,
            LocalTextSelectionColors provides
                TextSelectionColors(
                    handleColor = AppColors.accent,
                    backgroundColor = AppColors.accent.copy(alpha = 0.28f),
                ),
            content = content,
        )
    }
}
