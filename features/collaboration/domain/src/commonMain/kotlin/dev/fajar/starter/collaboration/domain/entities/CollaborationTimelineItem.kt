package dev.fajar.starter.collaboration.domain.entities

enum class CollaborationMessageKind {
    TEXT,
    REDACTED,
    UNABLE_TO_DECRYPT,
    UNSUPPORTED,
}

enum class CollaborationMessageDelivery {
    PENDING,
    FAILED,
    SENT,
}

/** A volatile SDK projection; logical IDs on local echoes require durable intent reconciliation. */
data class CollaborationTimelineItem(
    val key: String,
    val eventId: String?,
    val transactionId: String?,
    val logicalId: String?,
    val sender: String,
    val timestamp: Long,
    val own: Boolean,
    val kind: CollaborationMessageKind,
    val body: String?,
    val edited: Boolean,
    val delivery: CollaborationMessageDelivery,
    val replyToEventId: String? = null,
) {
    override fun toString() = "CollaborationTimelineItem([redacted])"
}
