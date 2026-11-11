package dev.fajar.starter.collaboration.domain.entities

/** Owned local turn selection; it grants no runtime, acquisition or wake completion authority. */
data class CollaborationSpaceIncomingWork(
    val snapshot: CollaborationSpaceIncomingDemandSnapshot?,
    val ready: List<CollaborationSpaceIncomingRoomDemand>,
    val scanPending: Boolean,
    val roomsPending: Boolean,
    val physicalReady: List<CollaborationSpaceIncomingPhysicalRoomDemand> = emptyList(),
    val inventoryReady: List<CollaborationSpaceIncomingSourceProgress> = emptyList(),
) {
    val turn: List<CollaborationSpaceIncomingTask>
        get() =
            (ready.map { CollaborationSpaceIncomingTask.Logical(it) } +
                    physicalReady.map { CollaborationSpaceIncomingTask.Physical(it) })
                .sortedBy { it.position }

    override fun toString() = "CollaborationSpaceIncomingWork([redacted])"
}
