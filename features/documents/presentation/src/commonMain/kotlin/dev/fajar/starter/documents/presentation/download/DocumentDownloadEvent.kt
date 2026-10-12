package dev.fajar.starter.documents.presentation.download

sealed interface DocumentDownloadEvent {
    data object Opened : DocumentDownloadEvent

    data object Closed : DocumentDownloadEvent

    data object DownloadRequested : DocumentDownloadEvent

    data object SaveRequested : DocumentDownloadEvent

    data object StopRequested : DocumentDownloadEvent

    data object RetryRequested : DocumentDownloadEvent

    data object VerificationRequested : DocumentDownloadEvent

    data object BackRequested : DocumentDownloadEvent
}
