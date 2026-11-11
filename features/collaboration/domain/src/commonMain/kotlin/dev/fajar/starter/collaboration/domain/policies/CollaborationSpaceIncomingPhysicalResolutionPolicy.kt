package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Transfer one exact physical turn without replacing unfinished work or source positions. */
fun resolveCollaborationSpaceIncomingPhysicalRoom(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    conversationId: String,
): AppResult<CollaborationSpaceIncomingPhysicalResolution> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
            !validCollaborationMessageId(conversationId)
    )
        return invalidPhysicalResolution()
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            physicalRooms = observed.physicalRooms.toList(),
            sources = observed.sources.toList(),
        )
    val physical =
        current.physicalRooms.singleOrNull {
            it.sourceId == captured.sourceId && it.roomId == captured.roomId
        }
    if (physical == null || physical.generation != captured.generation)
        return AppResult.Success(
            CollaborationSpaceIncomingPhysicalResolution(
                current,
                CollaborationSpaceIncomingBindingResolution.Obsolete,
            )
        )
    if (physical != captured) return invalidPhysicalResolution()
    // A null source on older logical work is not evidence that it covers this SDK source.
    // Even the same source can have an unfinished provider/key pass. Keep both requirements.
    if (current.rooms.any { it.conversationId == conversationId })
        return AppResult.Success(
            CollaborationSpaceIncomingPhysicalResolution(
                current,
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
            )
        )
    val room =
        CollaborationSpaceIncomingRoomDemand(
            conversationId,
            captured.generation,
            captured.position,
            captured.headEventId,
            CollaborationSpaceIncomingPhase.Latest,
            captured.retry,
            captured.sourceId,
        )
    val next =
        current.copy(
            rooms = (current.rooms + room).sortedBy { it.position },
            physicalRooms = current.physicalRooms.filterNot { it == captured },
        )
    return if (validCollaborationSpaceIncomingDemand(next))
        AppResult.Success(
            CollaborationSpaceIncomingPhysicalResolution(
                next,
                CollaborationSpaceIncomingBindingResolution.Queued,
            )
        )
    else invalidPhysicalResolution()
}

private fun invalidPhysicalResolution() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_binding_changed",
        FailureKind.Conflict,
    )
