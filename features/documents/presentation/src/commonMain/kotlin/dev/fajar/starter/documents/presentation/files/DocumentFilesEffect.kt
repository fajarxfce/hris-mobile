package dev.fajar.starter.documents.presentation.files

sealed interface DocumentFilesEffect {
    data object Back : DocumentFilesEffect

    data object VerifyAccount : DocumentFilesEffect
}
