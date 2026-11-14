package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Detach the retained page before any admission/storage suspension. */
fun captureCollaborationSpaceInboxHistoryCheckpoint(
    value: CollaborationSpaceInboxHistoryCheckpoint
): AppResult<CollaborationSpaceInboxHistoryCheckpoint> {
    if (
        !validCollaborationMessageId(value.version.generation) ||
            value.version.revision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            !validCollaborationSpaceInboxHistoryProgress(value.progress)
    )
        return historyProgressFailure(FailureKind.Unexpected)
    val pass = value.progress.pass ?: return AppResult.Success(value)
    val work =
        when (val traversal = pass.traversal) {
            is CollaborationSpaceInboxHistoryTraversal.Catalog ->
                traversal.copy(
                    batch = traversal.batch?.let { it.copy(remaining = it.remaining.toList()) }
                )
            is CollaborationSpaceInboxHistoryTraversal.Sources ->
                traversal.copy(
                    batch = traversal.batch?.let { it.copy(remaining = it.remaining.toList()) }
                )
            else -> traversal
        }
    return AppResult.Success(
        value.copy(progress = value.progress.copy(pass = pass.copy(traversal = work)))
    )
}

/** Typed actions select the one raw guard needed for their proposed transition. No I/O or retry. */
fun collaborationSpaceInboxHistoryMutation(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationSpaceInboxHistoryCheckpoint?,
    change: CollaborationSpaceInboxHistoryChange,
): AppResult<CollaborationSpaceInboxHistoryMutation> {
    if (
        !validCollaborationSpaceCatalogRequest(access, null, 16) ||
            !collaborationSpaceInboxHistoryDestinationCovers(change.destination, change.destination)
    )
        return historyProgressFailure(FailureKind.Validation)
    val captured =
        expected?.let {
            when (val result = captureCollaborationSpaceInboxHistoryCheckpoint(it)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        }
    val current = captured?.progress
    val next =
        if (change is CollaborationSpaceInboxHistoryChange.Observe) {
            if (change.trigger.destinationGeneration != change.destination?.generation)
                return historyProgressFailure(FailureKind.Validation)
            observeCollaborationSpaceInboxHistory(current, change.trigger, change.newPassId)
        } else {
            if (
                current == null ||
                    current.latest.destinationGeneration != change.destination?.generation
            )
                return historyProgressFailure(FailureKind.Conflict)
            changeCollaborationSpaceInboxHistoryTraversal(access, current, change)
        }
    if (next is AppResult.Failed) return next
    val replacement = (next as AppResult.Success).value
    if (
        captured != null &&
            captured.version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER &&
            captured.progress != replacement
    )
        return historyProgressFailure(FailureKind.Conflict)
    val source = (change as? CollaborationSpaceInboxHistoryChange.AcknowledgeSource)?.after
    val guard =
        source?.let { CollaborationSpaceInboxHistoryGuard.Source(it) }
            ?: CollaborationSpaceInboxHistoryGuard.Destination(change.destination)
    return AppResult.Success(CollaborationSpaceInboxHistoryMutation(captured, replacement, guard))
}

private fun changeCollaborationSpaceInboxHistoryTraversal(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceInboxHistoryProgress,
    change: CollaborationSpaceInboxHistoryChange,
): AppResult<CollaborationSpaceInboxHistoryProgress> =
    when (change) {
        is CollaborationSpaceInboxHistoryChange.Observe ->
            historyProgressFailure(FailureKind.Conflict)
        is CollaborationSpaceInboxHistoryChange.CaptureCatalog -> {
            val traversal =
                current.pass?.traversal as? CollaborationSpaceInboxHistoryTraversal.Catalog
            if (traversal == null) historyProgressFailure(FailureKind.Conflict)
            else if (!validCollaborationSpaceCatalogRead(access, traversal.after, 16, change.page))
                historyProgressFailure(FailureKind.Validation)
            else
                captureCollaborationSpaceInboxHistoryBatch(
                    current,
                    change.page.items.map { it.id },
                    change.page.next,
                )
        }
        is CollaborationSpaceInboxHistoryChange.CaptureSources -> {
            val traversal =
                current.pass?.traversal as? CollaborationSpaceInboxHistoryTraversal.Sources
            if (traversal == null) historyProgressFailure(FailureKind.Conflict)
            else
                when (
                    val page =
                        captureCollaborationSpaceInboxHistorySources(
                            access,
                            traversal.after,
                            16,
                            change.page,
                        )
                ) {
                    is AppResult.Failed -> page
                    is AppResult.Success ->
                        captureCollaborationSpaceInboxHistoryBatch(
                            current,
                            page.value.sources.map { it.progress.conversationId },
                            page.value.next,
                        )
                }
        }
        is CollaborationSpaceInboxHistoryChange.AcknowledgeSource ->
            acknowledgeCollaborationSpaceInboxHistorySource(
                access,
                current,
                change.conversationId,
                change.before,
                change.after,
            )
        is CollaborationSpaceInboxHistoryChange.RestartCatalog ->
            restartCollaborationSpaceInboxHistoryCatalog(current)
        is CollaborationSpaceInboxHistoryChange.Resume ->
            resumeCollaborationSpaceInboxHistory(current)
        is CollaborationSpaceInboxHistoryChange.Complete ->
            completeCollaborationSpaceInboxHistory(current)
    }
