package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess

sealed interface ChatThreadEffect {
    data object Back : ChatThreadEffect

    data object Setup : ChatThreadEffect

    data object ScrollToNewest : ChatThreadEffect

    data class CopyMessages(
        val owner: CollaborationLocalAccess,
        val token: String,
        val text: String,
    ) : ChatThreadEffect {
        override fun toString() = "CopyMessages([redacted])"
    }
}
