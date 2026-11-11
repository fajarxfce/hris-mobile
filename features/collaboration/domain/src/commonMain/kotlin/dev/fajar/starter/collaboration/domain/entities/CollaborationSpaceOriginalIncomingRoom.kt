package dev.fajar.starter.collaboration.domain.entities

/** Captured original work; Company and original device remain part of its history coordinates. */
data class CollaborationSpaceOriginalIncomingRoom(
    val captured: CollaborationSpaceIncomingRoomDemand,
    val conversation: CollaborationSpaceOriginalConversation,
    val traversal: CollaborationHistoryTraversal,
) {
    override fun toString() = "CollaborationSpaceOriginalIncomingRoom([redacted])"
}
