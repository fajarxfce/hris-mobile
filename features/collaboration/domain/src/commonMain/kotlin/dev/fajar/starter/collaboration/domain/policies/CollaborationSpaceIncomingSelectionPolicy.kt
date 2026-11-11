package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** One combined FIFO turn; source selection never hides another original's pending work. */
fun selectCollaborationSpaceIncomingWork(
    observed: CollaborationSpaceIncomingDemandSnapshot?,
    now: Long,
    source: CollaborationSpaceIncomingSource? = null,
): AppResult<CollaborationSpaceIncomingWork> {
    if (now !in 0..9_007_199_254_440_991L) return invalidIncomingSelection(FailureKind.Validation)
    if (source != null && !validCollaborationSpaceIncomingSource(source))
        return invalidIncomingSelection(FailureKind.Validation)
    val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
    if (
        observed != null &&
            (snapshot == null ||
                !validCollaborationMessageId(snapshot.generation) ||
                !validCollaborationSpaceIncomingDemand(snapshot.demand))
    )
        return invalidIncomingSelection()
    val demand = snapshot?.demand
    val matching = demand?.sources?.singleOrNull { it.source.storeId == source?.storeId }
    if (matching != null && matching.source != source) return invalidIncomingSelection()
    val logical =
        demand?.rooms.orEmpty().filter {
            (source == null || it.sourceId == null || it.sourceId == source.storeId) &&
                eligibleCollaborationSpaceIncomingRoom(it, now)
        }
    val physical =
        demand?.physicalRooms.orEmpty().filter {
            (source == null || it.sourceId == source.storeId) &&
                eligibleCollaborationSpaceIncomingPhysicalRoom(it, now)
        }
    val positions =
        (logical.map { it.position } + physical.map { it.position }).sorted().take(4).toSet()
    return AppResult.Success(
        CollaborationSpaceIncomingWork(
            snapshot = snapshot,
            ready = logical.filter { it.position in positions },
            scanPending =
                demand != null &&
                    (demand.rescanGeneration != null || demand.scan != null) &&
                    demand.rooms.size + demand.physicalRooms.size < 64,
            roomsPending =
                demand != null &&
                    (demand.rooms.isNotEmpty() ||
                        demand.physicalRooms.isNotEmpty() ||
                        demand.sources.any { it.inventoryGeneration != null }),
            physicalReady = physical.filter { it.position in positions },
            inventoryReady =
                if (demand != null && demand.rooms.size + demand.physicalRooms.size < 64)
                    demand.sources.filter {
                        (source == null || it.source == source) &&
                            eligibleCollaborationSpaceInventory(it, now)
                    }
                else emptyList(),
        )
    )
}

private fun invalidIncomingSelection(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_incoming_demand_changed", kind)
