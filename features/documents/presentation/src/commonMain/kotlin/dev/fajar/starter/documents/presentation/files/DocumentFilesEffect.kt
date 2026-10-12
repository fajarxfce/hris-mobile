package dev.fajar.starter.documents.presentation.files

sealed interface DocumentFilesEffect {
    data class OpenDownload(val id: String) : DocumentFilesEffect

    data object Back : DocumentFilesEffect

    data object VerifyAccount : DocumentFilesEffect

    data class OpenFile(val id: String) : DocumentFilesEffect
}
