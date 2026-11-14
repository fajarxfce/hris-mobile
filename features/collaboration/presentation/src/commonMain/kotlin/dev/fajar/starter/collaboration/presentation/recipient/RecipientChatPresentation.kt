package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/** Route-owned display continuity only; never a durable command journal or acknowledgement. */
data class RecipientChatPresentation(
    val pending: List<RecipientChatPendingSubmission> = emptyList(),
    val represented: List<RecipientChatPendingSubmission> = emptyList(),
) {
    override fun toString() = "RecipientChatPresentation([redacted])"
}

data class RecipientChatPendingSubmission(
    val original: CollaborationSpaceRecipientSubmission,
    val durable: Boolean = false,
    val failure: Failure? = null,
    val record: CollaborationSpaceMessageRecord? = null,
) {
    override fun toString() = "RecipientChatPendingSubmission([redacted])"
}
