package dev.fajar.starter.collaboration.domain.entities

/** Canonical conversation identity stays distinct from the physical content partition. */
data class CollaborationSpaceThreadHistory(
    val access: CollaborationSpaceLocalAccess,
    val conversationId: String,
    val location: CollaborationSpaceHistoryLocation,
    val page: CollaborationHistoryPage,
) {
    override fun toString() = "CollaborationSpaceThreadHistory([redacted])"
}
