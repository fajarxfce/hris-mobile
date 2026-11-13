package dev.fajar.starter.collaboration.domain.entities

/** One explicit gesture. Repeated attempts retain the same ID, target and reviewed text. */
data class CollaborationSpaceEditRequest(
    val id: String,
    val conversationId: String,
    val reviewed: CollaborationEditCandidate,
    val text: String,
) {
    override fun toString() = "CollaborationSpaceEditRequest([redacted])"
}

/**
 * Ephemeral prepared write; the repository persists the immutable intent and projection together.
 */
data class CollaborationSpacePreparedEdit(
    val intent: CollaborationSpaceMessageIntent,
    val publication: CollaborationSpaceInboxPublication,
) {
    override fun toString() = "CollaborationSpacePreparedEdit([redacted])"
}
