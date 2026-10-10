package dev.fajar.starter.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.composefluent.FluentTheme

object AppColors {
    val canvas: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFF14171C) else Color(0xFFF5F7FA)

    val surface: Color
        @Composable get() = if (FluentTheme.colors.darkMode) Color(0xFF20242B) else Color.White

    val text: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFFF3F5FA) else Color(0xFF172233)

    val muted: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFFA6B1C1) else Color(0xFF637083)

    val border: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFF363C46) else Color(0xFFE3E8EF)

    val accent: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFF70B9FF) else Color(0xFF0067B8)

    val tint: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFF17354D) else Color(0xFFEAF3FC)

    val error: Color
        @Composable
        get() = if (FluentTheme.colors.darkMode) Color(0xFFFFB4AB) else Color(0xFFB3261E)
}
