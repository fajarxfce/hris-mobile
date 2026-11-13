package dev.fajar.starter.collaboration.presentation.spacethread

sealed interface SpaceChatThreadEvent {
    data object Prepared : SpaceChatThreadEvent

    data object Entered : SpaceChatThreadEvent

    data object Stopped : SpaceChatThreadEvent

    data class DraftChanged(val text: String) : SpaceChatThreadEvent {
        override fun toString() = "DraftChanged([redacted])"
    }

    data object SendRequested : SpaceChatThreadEvent

    data object DraftRetryRequested : SpaceChatThreadEvent

    data object BackRequested : SpaceChatThreadEvent
}

sealed interface SpaceChatThreadEffect {
    data object Back : SpaceChatThreadEffect
}
