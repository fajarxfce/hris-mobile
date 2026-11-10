package dev.fajar.starter.collaboration.domain.entities

/** Current local binding for one still-current queued capture; grants no provider admission. */
data class CollaborationSpaceIncomingRoom(
    val captured: CollaborationSpaceIncomingRoomDemand,
    val conversation: CollaborationSpaceConversation,
    val traversal: CollaborationHistoryTraversal,
) {
    override fun toString() = "CollaborationSpaceIncomingRoom([redacted])"
}
