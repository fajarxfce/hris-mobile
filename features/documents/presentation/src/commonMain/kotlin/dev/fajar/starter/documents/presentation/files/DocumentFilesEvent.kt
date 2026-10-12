package dev.fajar.starter.documents.presentation.files

sealed interface DocumentFilesEvent {
    data object Opened : DocumentFilesEvent

    data object Closed : DocumentFilesEvent

    data object RetryRequested : DocumentFilesEvent

    data object ImportRequested : DocumentFilesEvent

    data class FileRequested(val id: String) : DocumentFilesEvent

    data class RemovalRequested(val id: String) : DocumentFilesEvent

    data object RemovalDismissed : DocumentFilesEvent

    data object RemovalConfirmed : DocumentFilesEvent

    data object VerificationRequested : DocumentFilesEvent

    data object BackRequested : DocumentFilesEvent
}
