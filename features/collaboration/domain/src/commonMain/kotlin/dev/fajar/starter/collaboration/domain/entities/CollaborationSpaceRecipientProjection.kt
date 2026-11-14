package dev.fajar.starter.collaboration.domain.entities

/** One owned recipient working copy and its retained originals, before canonical transfer. */
data class CollaborationSpaceRecipientProjection(
    val draft: CollaborationSpaceRecipientDraft?,
    val submissions: List<CollaborationSpaceRecipientSubmission>,
) {
    override fun toString() = "CollaborationSpaceRecipientProjection([redacted])"
}
