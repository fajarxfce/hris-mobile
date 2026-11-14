package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Physical index revisions and unrelated command inventory never restart a failed history pass. */
fun scheduleCollaborationSpaceInboxHistory(
    current: CollaborationSpaceInboxHistorySchedule,
    frame: CollaborationSpaceInboxHistoryFrame,
): AppResult<CollaborationSpaceInboxHistoryScheduleDecision> {
    if (
        !validCollaborationSpaceInboxHistoryTrigger(frame.trigger) ||
            !collaborationSpaceInboxHistoryDestinationCovers(
                frame.destination,
                frame.destination,
            ) ||
            frame.trigger.destinationGeneration != frame.destination?.generation
    )
        return historyProgressFailure(FailureKind.Validation)
    if (
        !current.continuing &&
            !current.ownerRecoveryPending &&
            !current.metadataRecoveryPending &&
            current.lastTrigger == frame.trigger
    )
        return AppResult.Success(CollaborationSpaceInboxHistoryScheduleDecision.Wait)
    return AppResult.Success(
        CollaborationSpaceInboxHistoryScheduleDecision.Run(
            frame,
            current.ownerRecoveryPending || current.metadataRecoveryPending,
            CollaborationSpaceInboxHistorySchedule(frame.trigger, false, false, false),
        )
    )
}

/** Consume recovery before suspension; only an actual advanced step permits local continuation. */
fun completeCollaborationSpaceInboxHistorySchedule(
    consumed: CollaborationSpaceInboxHistorySchedule,
    status: CollaborationSpaceInboxHistoryTurnStatus,
) = consumed.copy(continuing = status == CollaborationSpaceInboxHistoryTurnStatus.Advanced)

/** A restored admitted metadata stream permits one recovery attempt, not a background timer. */
fun suspendCollaborationSpaceInboxHistorySchedule(current: CollaborationSpaceInboxHistorySchedule) =
    current.copy(continuing = false, metadataRecoveryPending = true)

/**
 * Exact optimistic-write contention defers this background projection; other failures remain
 * failures.
 */
fun collaborationSpaceInboxHistoryFailureStatus(
    failure: Failure
): CollaborationSpaceInboxHistoryTurnStatus =
    if (failure.kind == FailureKind.Conflict && failure.code == "client.collaboration_inbox_busy")
        CollaborationSpaceInboxHistoryTurnStatus.Contended
    else CollaborationSpaceInboxHistoryTurnStatus.Failed
