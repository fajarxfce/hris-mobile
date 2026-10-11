package dev.fajar.starter.inbox.presentation.list

sealed interface InboxEvent {
    data object Entered : InboxEvent

    data object Stopped : InboxEvent

    data class PageRequested(val after: String?) : InboxEvent

    data object RefreshRequested : InboxEvent

    data class ItemSelected(val id: String) : InboxEvent

    data object BackRequested : InboxEvent

    data object VerificationRequested : InboxEvent
}
