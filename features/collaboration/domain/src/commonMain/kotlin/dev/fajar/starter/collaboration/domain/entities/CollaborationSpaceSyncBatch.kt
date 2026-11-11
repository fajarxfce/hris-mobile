package dev.fajar.starter.collaboration.domain.entities

/** Detached facts from one processed response of the exact installed original SDK store. */
data class CollaborationSpaceSyncBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val source: CollaborationSpaceIncomingSource,
    val previousPosition: String?,
    val nextPosition: String,
    val rooms: List<CollaborationSpaceIncomingRoomHint>,
    val inventoryRequired: Boolean,
) {
    override fun toString() = "CollaborationSpaceSyncBatch([redacted])"
}

/** A physical locator requires admitted resolution before it can identify logical content. */
data class CollaborationSpaceIncomingRoomHint(
    val roomId: String,
    val headEventId: String?,
    val membership: CollaborationSpaceIncomingMembership,
    val limited: Boolean,
) {
    override fun toString() = "CollaborationSpaceIncomingRoomHint([redacted])"
}

/** A wake is needed only for newly requested work, independently of cursor advancement. */
data class CollaborationSpaceIncomingBatchRetention(
    val demand: CollaborationSpaceIncomingDemand,
    val wakeRequired: Boolean,
)
