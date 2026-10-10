package dev.fajar.starter.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** Short, interruptible transitions; zero durations retain every interaction without motion. */
data class AppMotion(
    val pageMillis: Int = 180,
    val tabMillis: Int = 140,
    val selectionMillis: Int = 120,
)

val LocalAppMotion = staticCompositionLocalOf { AppMotion() }
