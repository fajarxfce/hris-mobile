package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** One page's exact cursor and additions confirm progress; later scan requests stay independent. */
fun confirmCollaborationSpaceIncomingScanWrite(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceIncomingDemand,
    next: CollaborationSpaceIncomingDemand,
    retained: CollaborationSpaceIncomingDemandSnapshot?,
    written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
): AppResult<Boolean> {
    if (
        !validCollaborationSpaceIncomingDemand(access, previous) ||
            previous.rescanGeneration == null ||
            !validCollaborationSpaceIncomingDemand(access, next) ||
            next.rooms.take(previous.rooms.size) != previous.rooms ||
            next.copy(
                rooms = previous.rooms,
                nextPosition = previous.nextPosition,
                scan = previous.scan,
                rescanGeneration = previous.rescanGeneration,
            ) != previous ||
            retained == null ||
            !validCollaborationMessageId(retained.generation) ||
            !validCollaborationSpaceIncomingDemand(access, retained.demand) ||
            retained.demand.nextPosition < previous.nextPosition
    )
        return failedScanWrite(written)
    val additions = next.rooms.drop(previous.rooms.size)
    if (
        additions.size > 4 ||
            next.nextPosition != previous.nextPosition + additions.size ||
            additions.any {
                it.phase != CollaborationSpaceIncomingPhase.Latest ||
                    it.sourceId != null ||
                    it.headEventId != null ||
                    it.retry != null
            }
    )
        return failedScanWrite(written)
    val current = retained.demand
    val active = previous.scan?.generation ?: previous.rescanGeneration
    val requestRetained =
        if (next.rescanGeneration == previous.rescanGeneration) current.rescanGeneration != null
        else
            current.rescanGeneration == next.rescanGeneration ||
                (current.rescanGeneration != null &&
                    current.rescanGeneration != previous.rescanGeneration &&
                    current.rescanGeneration != active)
    val sourceChanged =
        previous.sources.any { original ->
            val source =
                current.sources.singleOrNull { it.source.storeId == original.source.storeId }
            (source != null && source.source != original.source) ||
                (original.inventoryGeneration != null && source?.inventoryGeneration == null)
        }
    if (sourceChanged) return failedScanWrite(written)
    if (
        current.scan == next.scan &&
            requestRetained &&
            additions.all { wanted ->
                current.rooms.singleOrNull { it.conversationId == wanted.conversationId } == wanted
            }
    )
        return AppResult.Success(true)
    if (written is AppResult.Failed) return written
    if ((written as AppResult.Success).value == null)
        return collaborationSpaceIncomingQueueContention()
    return failedScanWrite(written)
}

private fun failedScanWrite(written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>) =
    if (written is AppResult.Failed) written
    else
        collaborationRecoveryFailure(
            "client.collaboration_incoming_scan_changed",
            FailureKind.Conflict,
        )
