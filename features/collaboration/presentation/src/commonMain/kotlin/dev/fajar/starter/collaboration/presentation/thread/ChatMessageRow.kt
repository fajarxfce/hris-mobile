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
    val canCancel: Boolean = false,
    val reference: CollaborationMessageReference? = null,
    /** Already filtered by the authenticated indexed local history query. */
    val visibilityKnown: Boolean = false,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
    /** Replacement progress does not change the original send/read identity. */
    val editDelivery: CollaborationMessageDelivery? = null,
    val failedEditIds: List<String> = emptyList(),
    val editEligibleUntil: Long? = null,
    /** Unapplied local changes in creation order, available through contextual copy. */
    val failedEditText: String? = null,
    /**
     * Captured local gesture before a durable command exists; never fabricated command evidence.
     */
    val composerSubmission: CollaborationComposerSubmission? = null,
) {
    override fun toString() = "ChatMessageRow([redacted])"
}
