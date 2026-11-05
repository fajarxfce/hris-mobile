package dev.fajar.starter.collaboration.domain.entities

/** An indexed retained replacement identity, never an original send or reader acknowledgement. */
data class CollaborationReplacementEventFact(
    val eventId: String,
    val sender: String,
    val timestamp: Long,
    val redacted: Boolean,
) {
    override fun toString() = "CollaborationReplacementEventFact([redacted])"
}
