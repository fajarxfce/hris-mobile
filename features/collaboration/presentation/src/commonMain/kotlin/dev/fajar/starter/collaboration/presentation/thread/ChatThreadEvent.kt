package dev.fajar.starter.collaboration.presentation.thread

sealed interface ChatThreadEvent {
    data object Entered : ChatThreadEvent

    data object Stopped : ChatThreadEvent

    data object BackRequested : ChatThreadEvent

    data object SetupRequested : ChatThreadEvent

    data object RefreshRequested : ChatThreadEvent

    data object OlderRequested : ChatThreadEvent

    data object NewerRequested : ChatThreadEvent

    data object LatestRequested : ChatThreadEvent

    data object SendRequested : ChatThreadEvent

    data object DraftRetryRequested : ChatThreadEvent

    data object DraftDialogDismissed : ChatThreadEvent

    data object DraftDialogConfirmed : ChatThreadEvent

    data class CancelRequested(val id: String) : ChatThreadEvent {
        override fun toString() = "CancelRequested([redacted])"
    }

    data object CancellationConfirmed : ChatThreadEvent

    data object CancellationDismissed : ChatThreadEvent

    data class DraftChanged(val text: String) : ChatThreadEvent {
        override fun toString() = "DraftChanged([redacted])"
    }
}
