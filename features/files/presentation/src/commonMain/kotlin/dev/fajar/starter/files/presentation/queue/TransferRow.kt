package dev.fajar.starter.files.presentation.queue

import androidx.compose.runtime.Immutable
import dev.fajar.starter.localization.AppString

@Immutable
data class TransferRow(
    val id: String,
    val name: String,
    val direction: AppString,
    val status: AppString,
    val progress: Int,
    val canPause: Boolean,
    val canResume: Boolean,
)
