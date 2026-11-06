package dev.fajar.starter.collaboration.domain.entities

/** Immutable original identity for an edit; never the identity of the replacement operation. */
data class CollaborationEditTarget(
    val roomId: String,
    val eventId: String,
    val originalLogicalId: String?,
    val sender: String,
    val originalContentHash: String,
    val targetAcceptedAt: Long,
    val firstAcceptedAt: Long,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
) {
    override fun toString() = "CollaborationEditTarget([redacted])"
}

/** Owned visible text at one coherent local checkpoint. No clock or transport admission. */
data class CollaborationEditCandidate(val target: CollaborationEditTarget, val text: String) {
    override fun toString() = "CollaborationEditCandidate([redacted])"
}
