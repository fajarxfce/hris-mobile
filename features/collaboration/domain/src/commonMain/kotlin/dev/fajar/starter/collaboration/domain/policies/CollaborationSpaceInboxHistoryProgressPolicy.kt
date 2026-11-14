package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationSpaceInboxHistoryTrigger(value: CollaborationSpaceInboxHistoryTrigger) =
    (value.historySignal == null || validCollaborationMessageId(value.historySignal)) &&
        (value.completedRepairDemand == null ||
            validCollaborationMessageId(value.completedRepairDemand)) &&
        (value.destinationGeneration == null ||
            validCollaborationMessageId(value.destinationGeneration)) &&
        (value.catalog == null ||
            validCollaborationMessageId(value.catalog.generation) &&
                value.catalog.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)

fun validCollaborationSpaceInboxHistoryProgress(
    value: CollaborationSpaceInboxHistoryProgress
): Boolean {
    if (!validCollaborationSpaceInboxHistoryTrigger(value.latest)) return false
    val completed = value.completed
    if (
        completed != null &&
            (!validCollaborationMessageId(completed.passId) ||
                !validCollaborationSpaceInboxHistoryTrigger(completed.trigger))
    )
        return false
    val pass = value.pass ?: return completed != null
    if (
        !validCollaborationMessageId(pass.id) ||
            pass.id == completed?.passId ||
            !validCollaborationSpaceInboxHistoryTrigger(pass.trigger) ||
            !validCollaborationSpaceInboxHistoryTrigger(pass.attemptedTrigger) ||
            pass.trigger.destinationGeneration != value.latest.destinationGeneration ||
            pass.attemptedTrigger.destinationGeneration != pass.trigger.destinationGeneration
    )
        return false
    val traversal = pass.traversal
    val after: String?
    val batch: CollaborationSpaceInboxHistoryBatch?
    when (traversal) {
        is CollaborationSpaceInboxHistoryTraversal.Catalog -> {
            after = traversal.after
            batch = traversal.batch
        }
        is CollaborationSpaceInboxHistoryTraversal.Sources -> {
            if (pass.catalogDeferred && !traversal.unfinished) return false
            after = traversal.after
            batch = traversal.batch
        }
        CollaborationSpaceInboxHistoryTraversal.Ready -> return !pass.catalogDeferred
        CollaborationSpaceInboxHistoryTraversal.Parked -> return true
    }
    if (after != null && after.length !in 1..4096) return false
    if (batch == null) return true
    return batch.remaining.size in 1..16 &&
        batch.remaining.all(::validCollaborationMessageId) &&
        batch.remaining.distinct().size == batch.remaining.size &&
        (batch.next == null || batch.next.length in 1..4096 && batch.next != after)
}

/**
 * Caller supplies fresh observations with explicit global CAS; opaque triggers cannot be ordered.
 */
fun observeCollaborationSpaceInboxHistory(
    current: CollaborationSpaceInboxHistoryProgress?,
    trigger: CollaborationSpaceInboxHistoryTrigger,
    newPassId: String,
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    if (
        !validCollaborationSpaceInboxHistoryTrigger(trigger) ||
            !validCollaborationMessageId(newPassId)
    )
        return historyProgressFailure(FailureKind.Validation)
    if (current != null && !validCollaborationSpaceInboxHistoryProgress(current))
        return historyProgressFailure(FailureKind.Unexpected)
    val active = current?.pass
    if (
        current != null &&
            active != null &&
            active.trigger.destinationGeneration == trigger.destinationGeneration
    ) {
        if (current.latest == trigger) return AppResult.Success(current)
        val resumed =
            if (active.traversal == CollaborationSpaceInboxHistoryTraversal.Parked)
                active.copy(
                    attemptedTrigger = trigger,
                    traversal = CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
                    catalogDeferred = false,
                )
            else active
        return AppResult.Success(current.copy(latest = trigger, pass = resumed))
    }
    if (active == null && current != null && current.completed?.trigger == trigger)
        return AppResult.Success(current.copy(latest = trigger))
    if (newPassId == active?.id || newPassId == current?.completed?.passId)
        return historyProgressFailure(FailureKind.Conflict)
    return AppResult.Success(
        CollaborationSpaceInboxHistoryProgress(
            trigger,
            current?.completed,
            CollaborationSpaceInboxHistoryPass(
                newPassId,
                trigger,
                trigger,
                CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
            ),
        )
    )
}

/** Explicit application-owner recovery, never a timer or self-produced metadata wake. */
fun resumeCollaborationSpaceInboxHistory(
    current: CollaborationSpaceInboxHistoryProgress
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    if (!validCollaborationSpaceInboxHistoryProgress(current))
        return historyProgressFailure(FailureKind.Unexpected)
    val pass = current.pass ?: return AppResult.Success(current)
    if (pass.traversal != CollaborationSpaceInboxHistoryTraversal.Parked)
        return AppResult.Success(current)
    return AppResult.Success(
        current.copy(
            pass =
                pass.copy(
                    attemptedTrigger = current.latest,
                    traversal = CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
                    catalogDeferred = false,
                )
        )
    )
}

/** Proposed completion still requires the repository's current destination guard. */
fun completeCollaborationSpaceInboxHistory(
    current: CollaborationSpaceInboxHistoryProgress
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    if (!validCollaborationSpaceInboxHistoryProgress(current))
        return historyProgressFailure(FailureKind.Unexpected)
    val pass = current.pass ?: return AppResult.Success(current)
    if (pass.traversal != CollaborationSpaceInboxHistoryTraversal.Ready)
        return historyProgressFailure(FailureKind.Conflict)
    return AppResult.Success(
        current.copy(
            completed = CollaborationSpaceInboxHistoryCompletion(pass.id, pass.trigger),
            pass = null,
        )
    )
}

internal fun historyProgressFailure(kind: FailureKind) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
