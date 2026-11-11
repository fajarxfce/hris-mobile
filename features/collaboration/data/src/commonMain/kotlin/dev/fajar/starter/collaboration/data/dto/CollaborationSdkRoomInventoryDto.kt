package dev.fajar.starter.collaboration.data.dto

/** Detached local SDK metadata. It grants no content access or joined-room capability. */
data class CollaborationSdkRoomInventoryDto(
    val afterRoomId: String?,
    val rooms: List<CollaborationSdkInventoryRoomDto>,
    val nextAfterRoomId: String?,
) {
    override fun toString() = "CollaborationSdkRoomInventoryDto([redacted])"
}

data class CollaborationSdkInventoryRoomDto(
    val roomId: String,
    val membership: CollaborationSdkInventoryMembership,
) {
    override fun toString() = "CollaborationSdkInventoryRoomDto([redacted])"
}

/** Preserve every SDK state; an invitation, knock or ban must never become an implicit join. */
enum class CollaborationSdkInventoryMembership {
    INVITED,
    JOINED,
    LEFT,
    KNOCKED,
    BANNED,
}
