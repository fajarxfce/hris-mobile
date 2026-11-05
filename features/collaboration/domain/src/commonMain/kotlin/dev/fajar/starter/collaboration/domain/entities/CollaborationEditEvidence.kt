package dev.fajar.starter.collaboration.domain.entities

/** A decrypted provider replacement, distinct from original send and visible revision evidence. */
data class CollaborationEditEvidence(
    val eventId: String,
    val sender: String,
    val targetEventId: String,
    val timestamp: Long,
    val contentHash: String,
    val operationId: String?,
    val eligible: Boolean,
    val redacted: Boolean,
) {
    override fun toString() = "CollaborationEditEvidence([redacted])"
}
