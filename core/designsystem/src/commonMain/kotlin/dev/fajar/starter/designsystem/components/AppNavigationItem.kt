package dev.fajar.starter.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

data class AppNavigationItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val badge: (@Composable () -> Unit)? = null,
)
