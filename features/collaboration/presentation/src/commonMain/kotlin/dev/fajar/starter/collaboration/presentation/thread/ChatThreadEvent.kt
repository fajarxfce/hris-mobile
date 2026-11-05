package dev.fajar.starter.collaboration.presentation.thread

sealed interface ChatThreadEvent {
    data class UnsentReplySelected(val id: String) : ChatThreadEvent

    data object LocalReadRetryRequested : ChatThreadEvent

    data object Entered : ChatThreadEvent

    data object Stopped : ChatThreadEvent

    data class ViewportChanged(val token: String?, val rowKeys: List<String>) : ChatThreadEvent {
        override fun toString() = "ViewportChanged([redacted])"
    }

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

    data class MessageSelected(val key: String) : ChatThreadEvent {
        override fun toString() = "MessageSelected([redacted])"
    }

    data object SelectionStarted : ChatThreadEvent

    data object SelectionCleared : ChatThreadEvent

    data object CopySelected : ChatThreadEvent

    data object DeleteSelected : ChatThreadEvent

    data object DeletionConfirmed : ChatThreadEvent

    data object DeletionDismissed : ChatThreadEvent

    data class DraftChanged(val text: String) : ChatThreadEvent {
        override fun toString() = "DraftChanged([redacted])"
    }
}
