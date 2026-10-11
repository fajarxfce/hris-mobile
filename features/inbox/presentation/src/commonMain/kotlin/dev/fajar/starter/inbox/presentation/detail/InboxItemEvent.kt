package dev.fajar.starter.inbox.presentation.detail

sealed interface InboxItemEvent {
    data object Entered : InboxItemEvent

    data object Stopped : InboxItemEvent

    data object RefreshRequested : InboxItemEvent

    data object BackRequested : InboxItemEvent

    data object VerificationRequested : InboxItemEvent
}
