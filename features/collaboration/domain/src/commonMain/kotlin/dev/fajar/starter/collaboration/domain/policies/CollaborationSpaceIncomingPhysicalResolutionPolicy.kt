package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Select the captured turn from a current owned snapshot; a newer turn remains untouched. */
fun selectCollaborationSpaceIncomingPhysicalSnapshot(
    access: CollaborationSpaceLocalAccess,
    source: CollaborationSpaceIncomingSource,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    snapshot: CollaborationSpaceIncomingDemandSnapshot?,
): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
    if (
        !validCollaborationSpaceIncomingSource(source) ||
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
            captured.sourceId != source.storeId
    )
        return invalidPhysicalResolution()
    if (snapshot == null) return AppResult.Success(null)
    if (
        !validCollaborationMessageId(snapshot.generation) ||
            !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
    )
        return invalidPhysicalResolution()
    val current =
        snapshot.demand.physicalRooms.singleOrNull {
            it.sourceId == captured.sourceId && it.roomId == captured.roomId
        }
    if (current == null || current.generation != captured.generation) return AppResult.Success(null)
    if (current != captured || snapshot.demand.sources.none { it.source == source })
        return invalidPhysicalResolution()
    return AppResult.Success(snapshot)
}

/** Existing published-conversation callers retain their explicit Space destination. */
fun resolveCollaborationSpaceIncomingPhysicalRoom(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    conversationId: String,
): AppResult<CollaborationSpaceIncomingPhysicalResolution> =
    resolveCollaborationSpaceIncomingPhysicalRoom(
        observed,
        captured,
        CollaborationSpaceIncomingTarget.Published(conversationId),
    )

/** Transfer one exact physical turn without replacing unfinished work or source positions. */
fun resolveCollaborationSpaceIncomingPhysicalRoom(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    target: CollaborationSpaceIncomingTarget,
): AppResult<CollaborationSpaceIncomingPhysicalResolution> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
            !validCollaborationSpaceIncomingTarget(target)
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
    if (current.rooms.any { it.target == target })
        return AppResult.Success(
            CollaborationSpaceIncomingPhysicalResolution(
                current,
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
            )
        )
    val room =
        CollaborationSpaceIncomingRoomDemand(
            target,
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

/** Interpret one owned readback; a known CAS miss is different from an uncertain write reply. */
fun confirmCollaborationSpaceIncomingPhysicalResolution(
    source: CollaborationSpaceIncomingSource,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    converted: CollaborationSpaceIncomingRoomDemand,
    retained: CollaborationSpaceIncomingDemand,
    casRejected: Boolean,
): AppResult<CollaborationSpaceIncomingBindingResolution> {
    if (
        !validCollaborationSpaceIncomingSource(source) ||
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
            !validCollaborationSpaceIncomingRoomDemand(converted) ||
            !validCollaborationSpaceIncomingDemand(retained) ||
            captured.sourceId != source.storeId ||
            converted.sourceId != source.storeId ||
            converted.generation != captured.generation ||
            converted.position != captured.position ||
            converted.headEventId != captured.headEventId ||
            converted.retry != captured.retry ||
            converted.phase != CollaborationSpaceIncomingPhase.Latest ||
            retained.sources.none { it.source == source }
    )
        return invalidPhysicalResolution()
    if (retained.rooms.singleOrNull { it.generation == converted.generation } == converted)
        return AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
    if (!casRejected) return invalidPhysicalResolution()
    val current =
        retained.physicalRooms.singleOrNull {
            it.sourceId == captured.sourceId && it.roomId == captured.roomId
        }
    if (current == null || current.generation != captured.generation)
        return AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete)
    if (current != captured) return invalidPhysicalResolution()
    return collaborationSpaceIncomingQueueContention()
}

private fun invalidPhysicalResolution() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_binding_changed",
        FailureKind.Conflict,
    )
