package dev.fajar.starter.collaboration.domain.entities

/** Stable original SDK store identity. Current native admission remains a separate requirement. */
data class CollaborationSpaceIncomingSource(
    val storeId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
) {
    override fun toString() = "CollaborationSpaceIncomingSource([redacted])"
}

/**
 * A position is retained only with the work it describes. An unresolved inventory generation
 * survives later responses; an owning inventory pass must explicitly discharge it.
 */
data class CollaborationSpaceIncomingSourceProgress(
    val source: CollaborationSpaceIncomingSource,
    val position: String,
    val inventoryGeneration: String? = null,
    val inventoryAfterRoomId: String? = null,
    val inventoryRetry: CollaborationSpaceIncomingRetry? = null,
) {
    override fun toString() = "CollaborationSpaceIncomingSourceProgress([redacted])"
}

enum class CollaborationSpaceIncomingMembership {
    Joined,
    Left,
}

/**
 * Unresolved physical work. The SDK locator grants no logical binding, content access or send
 * capability. Source identity and FIFO position remain attached until admitted resolution.
 */
data class CollaborationSpaceIncomingPhysicalRoomDemand(
    val sourceId: String,
    val roomId: String,
    val generation: String,
    val position: Long,
    val headEventId: String?,
    val membership: CollaborationSpaceIncomingMembership,
    val limited: Boolean,
    val retry: CollaborationSpaceIncomingRetry? = null,
) {
    override fun toString() = "CollaborationSpaceIncomingPhysicalRoomDemand([redacted])"
}
