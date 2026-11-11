package dev.fajar.starter.collaboration.domain.entities

/** A scoped physical lookup. Real conversation metadata must still admit its binding. */
data class CollaborationSpacePhysicalRoomView(
    val owner: CollaborationSpaceOwner,
    val conversationId: String,
    val provisioning: CollaborationSpaceRoomStatus,
    val room: CollaborationSpacePublishedRoom,
) {
    override fun toString() = "CollaborationSpacePhysicalRoomView([metadata])"
}
