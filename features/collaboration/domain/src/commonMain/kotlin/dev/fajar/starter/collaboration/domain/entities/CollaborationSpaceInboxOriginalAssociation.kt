package dev.fajar.starter.collaboration.domain.entities

/** Display identity proven by the retained original; this is not a delivery receipt. */
data class CollaborationSpaceInboxOriginalAssociation(
    val source: CollaborationSpaceInboxSource,
    val timestamp: Long,
    val delivery: CollaborationMessageDelivery,
) {
    override fun toString() = "CollaborationSpaceInboxOriginalAssociation([redacted])"
}
