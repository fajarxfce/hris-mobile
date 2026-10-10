package dev.fajar.starter.designsystem.platform

import androidx.compose.runtime.Composable

/** A rendering environment value, like system colors or window insets; no application policy. */
@Composable expect fun isSystemInReducedMotion(): Boolean
