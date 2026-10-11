package dev.fajar.starter.inbox.presentation.badge

sealed interface InboxBadgeEvent {
    data object Entered : InboxBadgeEvent

    data object Stopped : InboxBadgeEvent
}
