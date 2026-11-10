package dev.fajar.starter.collaboration.domain.entities

/** Owned local turn selection; it grants no runtime, acquisition or wake completion authority. */
data class CollaborationSpaceIncomingWork(
    val snapshot: CollaborationSpaceIncomingDemandSnapshot?,
    val ready: List<CollaborationSpaceIncomingRoomDemand>,
    val scanPending: Boolean,
    val roomsPending: Boolean,
) {
    override fun toString() = "CollaborationSpaceIncomingWork([redacted])"
}
