package dev.fajar.starter.collaboration.presentation.replies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSavedReply

/** Display data only; notification credentials and send intent remain in the owning use cases. */
data class ChatUnsentReply(val id: String, val text: String) {
    override fun toString() = "ChatUnsentReply([redacted])"
}

internal val CollaborationSavedReply.needsAttention: Boolean
    get() = input.review != null || input.resolution != null

internal fun CollaborationSavedReply.unsentDisplay() =
    ChatUnsentReply(input.id, input.resolution?.text ?: input.draft ?: input.candidate.text)
