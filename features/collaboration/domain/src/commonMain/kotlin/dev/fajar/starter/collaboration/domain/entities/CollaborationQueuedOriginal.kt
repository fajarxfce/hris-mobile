package dev.fajar.starter.collaboration.domain.entities

/** Exact native queue fact. The data adapter fingerprints all original content under its sender. */
data class CollaborationQueuedOriginal(
    val transactionId: String,
    val logicalId: String?,
    val origin: CollaborationMessageOrigin,
    val eventType: String,
    val serializedContent: String,
) {
    override fun toString() = "CollaborationQueuedOriginal([redacted])"
}

/** Both outcomes leave the externally confirmed original out of the paused queue. */
enum class CollaborationQueueSettlement {
    Settled,
    AlreadyAbsent,
}
