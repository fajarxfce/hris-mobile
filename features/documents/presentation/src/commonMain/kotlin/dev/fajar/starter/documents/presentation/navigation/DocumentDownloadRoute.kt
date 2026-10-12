package dev.fajar.starter.documents.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable data class DocumentDownloadRoute(val fileId: String, val start: Boolean = false)
