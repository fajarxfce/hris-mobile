package dev.fajar.starter.designsystem.components

import androidx.compose.runtime.Immutable

@Immutable
data class AppDateChoice(
    val day: Int,
    val label: String,
    val description: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)
