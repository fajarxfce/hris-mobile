package dev.fajar.starter.collaboration.presentation.participants

sealed interface ChatParticipantsEffect {
    data object Back : ChatParticipantsEffect

    data object ScrollToStart : ChatParticipantsEffect

    data class OpenConversation(val id: String) : ChatParticipantsEffect
}
