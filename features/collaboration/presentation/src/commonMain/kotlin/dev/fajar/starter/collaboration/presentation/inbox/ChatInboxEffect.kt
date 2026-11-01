package dev.fajar.starter.collaboration.presentation.inbox

sealed interface ChatInboxEffect {
    data object NewChat : ChatInboxEffect

    data object Back : ChatInboxEffect

    data object ScrollToStart : ChatInboxEffect

    data class OpenConversation(val id: String) : ChatInboxEffect
}
