package dev.fajar.starter.designsystem.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale

// Desktop hosts can provide a MotionDurationScale or explicitly set AppTheme.reduceMotion.
@Composable
actual fun isSystemInReducedMotion(): Boolean =
    rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
