package dev.fajar.starter.inbox.presentation.detail

sealed interface InboxItemEvent {
    data object Entered : InboxItemEvent

    data object Stopped : InboxItemEvent

    data object RefreshRequested : InboxItemEvent

    data object BackRequested : InboxItemEvent

    data object VerificationRequested : InboxItemEvent

    data object ReadRequested : InboxItemEvent

    data object AcknowledgementRequested : InboxItemEvent

    data object AcknowledgementConfirmed : InboxItemEvent

    data object AcknowledgementCancelled : InboxItemEvent

    data object RetryActionRequested : InboxItemEvent

    data object ReviewActionRequested : InboxItemEvent
}
