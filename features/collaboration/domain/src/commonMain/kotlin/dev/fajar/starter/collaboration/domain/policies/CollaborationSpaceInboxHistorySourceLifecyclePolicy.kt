package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** A new pass cannot acknowledge an unfinished interval captured by an older pass. */
fun prepareCollaborationSpaceInboxHistorySource(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    owner: CollaborationHistoryChangeOwner,
    passId: String,
    destination: CollaborationSpaceInboxVersion?,
    current: CollaborationSpaceInboxHistorySourceCheckpoint?,
): AppResult<CollaborationSpaceInboxHistorySourceProgress> {
    val initial =
        CollaborationSpaceInboxHistorySourceProgress(
            conversationId,
            owner,
            destination?.generation,
            passId,
            null,
            false,
            null,
        )
    // Only structural validation: storage supplies the actual physical generation on insertion.
    if (
        !validCollaborationSpaceInboxHistorySourceCheckpoint(
            access,
            CollaborationSpaceInboxHistorySourceCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(passId, 0),
                initial,
            ),
        ) || !collaborationSpaceInboxHistoryDestinationCovers(destination, destination)
    )
        return invalidSourceLifecycle(FailureKind.Validation)
    if (current == null) return AppResult.Success(initial)
    if (
        !validCollaborationSpaceInboxHistorySourceCheckpoint(access, current) ||
            current.progress.conversationId != conversationId ||
            current.progress.owner != owner
    )
        return invalidSourceLifecycle(FailureKind.Unexpected)
    val previous = current.progress
    if (previous.destinationGeneration == destination?.generation && previous.passId == passId)
        return AppResult.Success(previous)
    if (current.version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER)
        return invalidSourceLifecycle(FailureKind.Conflict)
    if (previous.destinationGeneration != destination?.generation) return AppResult.Success(initial)
    if (!previous.historyComplete || previous.pendingWindow?.complete != true)
        return invalidSourceLifecycle(FailureKind.Conflict)
    return AppResult.Success(initial.copy(historyCursor = previous.historyCursor))
}

private fun invalidSourceLifecycle(kind: FailureKind) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
