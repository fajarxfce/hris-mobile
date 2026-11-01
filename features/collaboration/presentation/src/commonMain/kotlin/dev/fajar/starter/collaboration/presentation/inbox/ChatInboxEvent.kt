package dev.fajar.starter.collaboration.presentation.inbox

sealed interface ChatInboxEvent {
    data object Entered : ChatInboxEvent

    data object Stopped : ChatInboxEvent

    data object RefreshRequested : ChatInboxEvent

    data object NextRequested : ChatInboxEvent

    data object PreviousRequested : ChatInboxEvent

    data object NewChatRequested : ChatInboxEvent

    data object BackRequested : ChatInboxEvent

    data class ConversationSelected(val id: String) : ChatInboxEvent
}
