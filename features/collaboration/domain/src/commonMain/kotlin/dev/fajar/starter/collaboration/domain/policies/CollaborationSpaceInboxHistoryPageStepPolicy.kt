package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Acquisition selects a checked action, never a completed cursor or a publication receipt. */
fun collaborationSpaceInboxHistoryPageStep(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationSpaceInboxHistorySourceCheckpoint,
    read: CollaborationSpaceHistoryChangesRead,
    restarted: Boolean,
): AppResult<CollaborationSpaceInboxHistoryPageStep> {
    if (
        !validCollaborationSpaceInboxHistorySourceCheckpoint(access, expected) ||
            read.owner != expected.progress.owner ||
            !validCollaborationHistoryChanges(
                read.owner,
                expected.progress.historyCursor,
                16,
                read.changes,
            )
    )
        return historyProgressFailure(FailureKind.Validation)
    if (
        expected.progress.historyComplete ||
            expected.version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER
    )
        return historyProgressFailure(FailureKind.Conflict)
    return when (val changes = read.changes) {
        CollaborationHistoryChanges.Restart -> {
            if (restarted || expected.progress.historyCursor == null)
                historyProgressFailure(FailureKind.Conflict)
            else AppResult.Success(CollaborationSpaceInboxHistoryPageStep.Restart(expected, read))
        }
        CollaborationHistoryChanges.Absent ->
            AppResult.Success(CollaborationSpaceInboxHistoryPageStep.Confirm(expected, read))
        is CollaborationHistoryChanges.Page -> {
            val captured = read.copy(changes = changes.copy(eventIds = changes.eventIds.toList()))
            if (changes.eventIds.isEmpty())
                AppResult.Success(
                    CollaborationSpaceInboxHistoryPageStep.Confirm(expected, captured)
                )
            else
                AppResult.Success(
                    CollaborationSpaceInboxHistoryPageStep.PublishHistory(
                        expected,
                        captured,
                        changes.eventIds.toList(),
                    )
                )
        }
    }
}

/**
 * Empty pending completion uses metadata; nonempty publication retains every captured reference.
 */
fun collaborationSpaceInboxHistoryPendingStep(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationSpaceInboxHistorySourceCheckpoint,
    read: CollaborationSpaceInboxHistoryPendingPage,
): AppResult<CollaborationSpaceInboxHistoryPageStep> {
    if (!validCollaborationSpaceInboxHistoryPendingPage(access, expected, 16, read))
        return historyProgressFailure(FailureKind.Validation)
    if (
        expected.progress.pendingWindow?.complete == true ||
            expected.version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER
    )
        return historyProgressFailure(FailureKind.Conflict)
    val captured = read.copy(references = read.references.toList())
    return AppResult.Success(
        if (captured.references.isEmpty())
            CollaborationSpaceInboxHistoryPageStep.CompletePending(expected, captured)
        else
            CollaborationSpaceInboxHistoryPageStep.PublishPending(
                expected,
                captured,
                captured.references.map { it.eventId },
            )
    )
}
