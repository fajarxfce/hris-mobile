package dev.fajar.starter.collaboration.presentation.replies

sealed interface SavedRepliesEffect {
    data object Back : SavedRepliesEffect

    data class OpenReply(val id: String) : SavedRepliesEffect

    data class OpenChat(val conversationId: String) : SavedRepliesEffect
}
