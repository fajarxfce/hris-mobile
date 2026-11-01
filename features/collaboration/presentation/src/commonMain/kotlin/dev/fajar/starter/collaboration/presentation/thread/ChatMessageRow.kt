package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

enum class ChatMessageOwnership {
    SELF,
    COLLEAGUE,
    UNKNOWN,
}

data class ChatMessageRow(
    val key: String,
    val eventId: String?,
    val localId: String?,
    val timestamp: Long,
    val ownership: ChatMessageOwnership,
    val kind: CollaborationMessageKind,
    val text: String?,
    val delivery: CollaborationMessageDelivery,
    val edited: Boolean = false,
) {
    override fun toString() = "ChatMessageRow([redacted])"
}
