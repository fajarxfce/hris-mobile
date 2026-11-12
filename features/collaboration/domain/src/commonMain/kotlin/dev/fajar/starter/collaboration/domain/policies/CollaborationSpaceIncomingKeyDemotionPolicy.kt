package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Transfers one exact pending primary task to a fresh recovery pass. The operation owner must prove
 * provider EOF and unresolved local references, then commit this replacement and a fresh wake in
 * ONE repository CAS. This is not clean primary completion or a storage receipt.
 */
fun planCollaborationSpaceIncomingKeyDemotion(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingRoomDemand,
    generation: String,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
            captured.phase != CollaborationSpaceIncomingPhase.Pending ||
            !validCollaborationMessageId(generation)
    )
        return invalidKeyDemotion()
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            sources = observed.sources.toList(),
            physicalRooms = observed.physicalRooms.toList(),
        )
    val room =
        current.rooms.singleOrNull { it.target == captured.target }
            ?: return AppResult.Success(current)
    if (room.generation != captured.generation) return AppResult.Success(current)
    if (room != captured) return invalidKeyDemotion()
    val existing = current.historyRecovery
    // A later unresolved wave must not replay a generation that may already have finished.
    if (generation == existing?.generation || generation == existing?.requestedGeneration)
        return invalidKeyDemotion()
    val requested = planCollaborationSpaceHistoryRecoveryRequest(existing, generation)
    if (requested is AppResult.Failed) return requested
    val next =
        current.copy(
            rooms = current.rooms.filterNot { it == room },
            historyRecovery = (requested as AppResult.Success).value,
        )
    return if (validCollaborationSpaceIncomingDemand(next)) AppResult.Success(next)
    else invalidKeyDemotion()
}

private fun invalidKeyDemotion() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_demand_changed",
        FailureKind.Conflict,
    )
