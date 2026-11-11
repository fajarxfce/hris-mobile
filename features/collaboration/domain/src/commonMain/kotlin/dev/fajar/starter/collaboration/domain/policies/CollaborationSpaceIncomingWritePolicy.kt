package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Only this logical transition is the witness; independent producer positions are not its receipt.
 */
fun confirmCollaborationSpaceIncomingRoomWrite(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceIncomingDemand,
    next: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingRoomDemand,
    retained: CollaborationSpaceIncomingDemandSnapshot?,
    written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
): AppResult<CollaborationSpaceIncomingRoomWrite> {
    if (
        !validCollaborationSpaceIncomingDemand(access, previous) ||
            !validCollaborationSpaceIncomingDemand(access, next) ||
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
            previous.rooms.singleOrNull { it.conversationId == captured.conversationId } !=
                captured ||
            next.copy(rooms = previous.rooms, nextPosition = previous.nextPosition) != previous ||
            next.rooms.filterNot { it.conversationId == captured.conversationId } !=
                previous.rooms.filterNot { it.conversationId == captured.conversationId } ||
            retained == null ||
            !validCollaborationMessageId(retained.generation) ||
            !validCollaborationSpaceIncomingDemand(access, retained.demand) ||
            retained.demand.nextPosition < previous.nextPosition
    )
        return failedIncomingWrite(written)
    val desired = next.rooms.singleOrNull { it.conversationId == captured.conversationId }
    if (
        desired != null &&
            (desired.generation != captured.generation ||
                desired.sourceId != captured.sourceId ||
                desired.headEventId != captured.headEventId)
    )
        return failedIncomingWrite(written)
    val current =
        retained.demand.rooms.singleOrNull { it.conversationId == captured.conversationId }
    val sourceEvidenceLost =
        previous.sources.any { original ->
            val currentSource =
                retained.demand.sources.singleOrNull {
                    it.source.storeId == original.source.storeId
                }
            (currentSource != null && currentSource.source != original.source) ||
                (original.inventoryGeneration != null && currentSource?.inventoryGeneration == null)
        }
    if (sourceEvidenceLost) return failedIncomingWrite(written)
    val generationElsewhere =
        retained.demand.rooms.any {
            it.generation == captured.generation && it.conversationId != captured.conversationId
        } || retained.demand.physicalRooms.any { it.generation == captured.generation }
    if (generationElsewhere) return failedIncomingWrite(written)
    if (desired != null && desired == current)
        return AppResult.Success(CollaborationSpaceIncomingRoomWrite.Applied)
    // Removal is witnessed by absence of this generation, including when newer work is retained.
    if (desired == null && current?.generation != captured.generation)
        return AppResult.Success(CollaborationSpaceIncomingRoomWrite.Applied)
    if (written is AppResult.Failed) return written
    if (current == null || current.generation != captured.generation)
        return AppResult.Success(CollaborationSpaceIncomingRoomWrite.Obsolete)
    if (current == captured && (written as AppResult.Success).value == null)
        return collaborationSpaceIncomingQueueContention()
    return failedIncomingWrite(written)
}

/** The caller retains current admission; this policy interprets only owned storage observations. */
private fun failedIncomingWrite(written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>) =
    if (written is AppResult.Failed) written
    else
        collaborationRecoveryFailure(
            "client.collaboration_incoming_write_changed",
            FailureKind.Conflict,
        )
