package dev.fajar.starter.documents.presentation.download

sealed interface DocumentDownloadEffect {
    data object VerifyAccount : DocumentDownloadEffect

    data object Back : DocumentDownloadEffect
}
