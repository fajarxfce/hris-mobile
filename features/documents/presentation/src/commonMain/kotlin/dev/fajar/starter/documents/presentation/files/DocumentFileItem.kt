package dev.fajar.starter.documents.presentation.files

import dev.fajar.starter.localization.AppString

data class DocumentFileItem(
    val id: String,
    val name: String,
    val description: String,
    val status: AppString,
    val progress: Float?,
    val openable: Boolean,
    val removable: Boolean,
)
