package dev.fajar.starter.collaboration.presentation.replies

sealed interface SavedRepliesEvent {
    data object Entered : SavedRepliesEvent

    data object Stopped : SavedRepliesEvent

    data object RefreshRequested : SavedRepliesEvent

    data object BackRequested : SavedRepliesEvent

    data object OpenChatRequested : SavedRepliesEvent

    data class DraftChanged(val text: String) : SavedRepliesEvent {
        override fun toString() = "DraftChanged([redacted])"
    }

    data object SaveDraftRequested : SavedRepliesEvent

    data object SendRequested : SavedRepliesEvent

    data object DiscardEditRequested : SavedRepliesEvent

    data object LeaveConfirmed : SavedRepliesEvent

    data object LeaveDismissed : SavedRepliesEvent

    data object DiscardRequested : SavedRepliesEvent

    data object DiscardConfirmed : SavedRepliesEvent

    data object DiscardDismissed : SavedRepliesEvent

    data class ReplySelected(val id: String) : SavedRepliesEvent
}
