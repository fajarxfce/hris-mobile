package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Defer only the captured physical generation; waiting for its prior logical pass needs a delay.
 */
fun deferCollaborationSpaceIncomingPhysicalRoom(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    now: Long,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingPhysicalRoom(captured)
    )
        return invalidPhysicalRetry()
    val retry =
        nextCollaborationSpaceIncomingRetry(captured.retry, now) ?: return invalidPhysicalRetry()
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
        return AppResult.Success(current)
    if (physical != captured) {
        // A lost response cannot move this same generation repeatedly or extend its retry.
        return if (
            physical.position > captured.position &&
                physical.retry != null &&
                physical.copy(position = captured.position, retry = captured.retry) == captured
        )
            AppResult.Success(current)
        else invalidPhysicalRetry()
    }
    if (current.nextPosition == 9_007_199_254_740_991L) return invalidPhysicalRetry()
    val next =
        current.copy(
            nextPosition = current.nextPosition + 1,
            physicalRooms =
                current.physicalRooms.filterNot { it == captured } +
                    captured.copy(position = current.nextPosition, retry = retry),
        )
    return if (validCollaborationSpaceIncomingDemand(next)) AppResult.Success(next)
    else invalidPhysicalRetry()
}

/** Exact deferral is a sufficient witness even after a producer updates independent cursors. */
fun confirmCollaborationSpaceIncomingPhysicalDeferral(
    source: CollaborationSpaceIncomingSource,
    captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    deferred: CollaborationSpaceIncomingPhysicalRoomDemand,
    retained: CollaborationSpaceIncomingDemand,
    casRejected: Boolean,
): AppResult<Boolean> {
    if (
        !validCollaborationSpaceIncomingSource(source) ||
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
            !validCollaborationSpaceIncomingPhysicalRoom(deferred) ||
            !validCollaborationSpaceIncomingDemand(retained) ||
            captured.sourceId != source.storeId ||
            retained.sources.none { it.source == source } ||
            deferred.position <= captured.position ||
            deferred.retry == null ||
            deferred.copy(position = captured.position, retry = captured.retry) != captured
    )
        return invalidPhysicalRetry()
    val current =
        retained.physicalRooms.singleOrNull {
            it.sourceId == captured.sourceId && it.roomId == captured.roomId
        }
    if (current == deferred) return AppResult.Success(true)
    if (!casRejected) return invalidPhysicalRetry()
    if (current == null || current.generation != captured.generation)
        return AppResult.Success(false)
    return if (current == captured) collaborationSpaceIncomingQueueContention()
    else invalidPhysicalRetry()
}

private fun invalidPhysicalRetry() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_physical_defer_changed",
        FailureKind.Conflict,
    )
