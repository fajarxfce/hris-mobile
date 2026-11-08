package dev.fajar.starter.collaboration.domain.entities

/** A retained display fact. It never authorizes delivery or changes an original message. */
data class CollaborationRecipientObservation(
    val conversationId: String,
    val revision: String,
    val ready: Boolean,
) {
    override fun toString() = "CollaborationRecipientObservation([redacted])"
}
