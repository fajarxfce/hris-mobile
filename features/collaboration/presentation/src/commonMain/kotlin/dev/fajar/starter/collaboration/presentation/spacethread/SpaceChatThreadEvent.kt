package dev.fajar.starter.collaboration.presentation.spacethread

sealed interface SpaceChatThreadEvent {
    data object Prepared : SpaceChatThreadEvent

    data object Entered : SpaceChatThreadEvent

    data object Stopped : SpaceChatThreadEvent

    data class DraftChanged(val text: String) : SpaceChatThreadEvent {
        override fun toString() = "DraftChanged([redacted])"
    }

    data object SendRequested : SpaceChatThreadEvent

    data object LocalReadRetryRequested : SpaceChatThreadEvent

    data object DraftRetryRequested : SpaceChatThreadEvent

    data class MessageSelected(val identity: SpaceChatMessageIdentity) : SpaceChatThreadEvent {
        override fun toString() = "MessageSelected([redacted])"
    }

    data object SelectionCleared : SpaceChatThreadEvent

    data object CopySelected : SpaceChatThreadEvent

    data object ReplySelected : SpaceChatThreadEvent

    data object ReplyCleared : SpaceChatThreadEvent

    data object ActionFailureDismissed : SpaceChatThreadEvent

    data object BackRequested : SpaceChatThreadEvent
}

sealed interface SpaceChatThreadEffect {
    data object Back : SpaceChatThreadEffect

    data object ScrollToNewest : SpaceChatThreadEffect

    data object FocusComposer : SpaceChatThreadEffect

    data class CopyMessages(val requestId: Long, val text: String) : SpaceChatThreadEffect {
        override fun toString() = "CopyMessages([redacted])"
    }
}
