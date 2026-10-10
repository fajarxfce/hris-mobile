package dev.fajar.starter.files.presentation.queue

import dev.fajar.starter.transfers.domain.entities.TransferAction

sealed interface FilesEvent {
    data object Started : FilesEvent

    data object Stopped : FilesEvent

    data object UploadRequested : FilesEvent

    data object DownloadRequested : FilesEvent

    data class TransferChanged(val id: String, val action: TransferAction) : FilesEvent

    data object BackRequested : FilesEvent
}
