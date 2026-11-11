package dev.fajar.starter.collaboration.domain.entities

/** Finite local metadata only; no content admission or durable inventory coverage is implied. */
data class CollaborationSpaceRoomInventoryPage(
    val runtime: CollaborationSpaceRuntimeReady,
    val source: CollaborationSpaceIncomingSource,
    val afterRoomId: String?,
    val rooms: List<CollaborationSpaceInventoryRoom>,
    val nextAfterRoomId: String?,
) {
    override fun toString() = "CollaborationSpaceRoomInventoryPage([redacted])"
}

data class CollaborationSpaceInventoryRoom(
    val roomId: String,
    val membership: CollaborationSpaceInventoryMembership,
) {
    override fun toString() = "CollaborationSpaceInventoryRoom([redacted])"
}

enum class CollaborationSpaceInventoryMembership {
    Invited,
    Joined,
    Left,
    Knocked,
    Banned,
}
