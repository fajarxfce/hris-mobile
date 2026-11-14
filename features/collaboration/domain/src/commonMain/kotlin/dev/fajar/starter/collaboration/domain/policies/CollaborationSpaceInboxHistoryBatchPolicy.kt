package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Retain IDs before any source visit. Empty catalog pages may have a continuation. */
fun captureCollaborationSpaceInboxHistoryBatch(
    current: CollaborationSpaceInboxHistoryProgress,
    conversationIds: List<String>,
    next: String?,
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    if (!validCollaborationSpaceInboxHistoryProgress(current))
        return historyProgressFailure(FailureKind.Unexpected)
    val ids = conversationIds.toList()
    if (
        ids.size > 16 ||
            ids.any { !validCollaborationMessageId(it) } ||
            ids.distinct().size != ids.size ||
            (next != null && next.length !in 1..4096)
    )
        return historyProgressFailure(FailureKind.Validation)
    val pass = current.pass ?: return historyProgressFailure(FailureKind.Conflict)
    val traversal = pass.traversal
    val captured = if (ids.isEmpty()) null else CollaborationSpaceInboxHistoryBatch(ids, next)
    val result =
        when (traversal) {
            is CollaborationSpaceInboxHistoryTraversal.Catalog -> {
                if (traversal.batch != null || next != null && next == traversal.after)
                    return historyProgressFailure(FailureKind.Conflict)
                if (captured != null) traversal.copy(batch = captured)
                else if (next != null) traversal.copy(after = next)
                else
                    CollaborationSpaceInboxHistoryTraversal.Sources(
                        null,
                        null,
                        pass.catalogDeferred,
                        false,
                    )
            }
            is CollaborationSpaceInboxHistoryTraversal.Sources -> {
                if (
                    traversal.batch != null ||
                        next != null && (next == traversal.after || ids.isEmpty())
                )
                    return historyProgressFailure(FailureKind.Conflict)
                if (captured != null) traversal.copy(batch = captured)
                else return finishCollaborationSpaceInboxHistoryRound(current, traversal)
            }
            else -> return historyProgressFailure(FailureKind.Conflict)
        }
    return AppResult.Success(current.copy(pass = pass.copy(traversal = result)))
}

/**
 * A failed catalog continuation yields to retained sources; a later catalog trigger remains
 * pending.
 */
fun restartCollaborationSpaceInboxHistoryCatalog(
    current: CollaborationSpaceInboxHistoryProgress
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    if (!validCollaborationSpaceInboxHistoryProgress(current))
        return historyProgressFailure(FailureKind.Unexpected)
    val pass = current.pass ?: return historyProgressFailure(FailureKind.Conflict)
    val catalog =
        pass.traversal as? CollaborationSpaceInboxHistoryTraversal.Catalog
            ?: return historyProgressFailure(FailureKind.Conflict)
    if (catalog.after == null || catalog.batch != null)
        return historyProgressFailure(FailureKind.Conflict)
    return AppResult.Success(
        current.copy(
            pass =
                pass.copy(
                    traversal =
                        CollaborationSpaceInboxHistoryTraversal.Sources(null, null, true, false),
                    catalogDeferred = true,
                )
        )
    )
}

/**
 * Every queued item is acknowledged once. Failures retain unfinished work, not successful progress.
 */
fun acknowledgeCollaborationSpaceInboxHistorySource(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceInboxHistoryProgress,
    conversationId: String,
    before: CollaborationSpaceInboxHistorySourceCheckpoint?,
    after: CollaborationSpaceInboxHistorySourceCheckpoint?,
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    if (!validCollaborationSpaceInboxHistoryProgress(current))
        return historyProgressFailure(FailureKind.Unexpected)
    if (
        !validCollaborationMessageId(conversationId) ||
            listOfNotNull(before, after).any {
                !validCollaborationSpaceInboxHistorySourceCheckpoint(access, it) ||
                    it.progress.conversationId != conversationId
            }
    )
        return historyProgressFailure(FailureKind.Validation)
    if (before != null && after != null && before.progress.owner != after.progress.owner)
        return historyProgressFailure(FailureKind.Conflict)
    val pass = current.pass ?: return historyProgressFailure(FailureKind.Conflict)
    if (after != null && after.progress.destinationGeneration != pass.trigger.destinationGeneration)
        return historyProgressFailure(FailureKind.Conflict)
    val traversal = pass.traversal
    val batch =
        when (traversal) {
            is CollaborationSpaceInboxHistoryTraversal.Catalog -> traversal.batch
            is CollaborationSpaceInboxHistoryTraversal.Sources -> traversal.batch
            else -> null
        } ?: return historyProgressFailure(FailureKind.Conflict)
    if (batch.remaining.first() != conversationId)
        return historyProgressFailure(FailureKind.Conflict)
    val remaining = batch.remaining.drop(1)
    val nextBatch = remaining.takeIf { it.isNotEmpty() }?.let { batch.copy(remaining = it) }
    val catalogDeferred =
        pass.catalogDeferred ||
            (traversal is CollaborationSpaceInboxHistoryTraversal.Catalog && after == null)
    val result =
        when (traversal) {
            is CollaborationSpaceInboxHistoryTraversal.Catalog -> {
                if (nextBatch != null) traversal.copy(batch = nextBatch)
                else if (batch.next != null) traversal.copy(after = batch.next, batch = null)
                else
                    CollaborationSpaceInboxHistoryTraversal.Sources(
                        null,
                        null,
                        catalogDeferred,
                        false,
                    )
            }
            is CollaborationSpaceInboxHistoryTraversal.Sources -> {
                val unfinished =
                    after == null ||
                        after.progress.passId != pass.id ||
                        !after.progress.historyComplete ||
                        after.progress.pendingWindow?.complete != true
                val visited =
                    traversal.copy(
                        batch = nextBatch,
                        unfinished = traversal.unfinished || unfinished,
                        advanced =
                            traversal.advanced ||
                                collaborationSpaceInboxHistoryVisitAdvanced(before, after),
                    )
                if (nextBatch != null) visited
                else if (batch.next != null) visited.copy(after = batch.next)
                else return finishCollaborationSpaceInboxHistoryRound(current, visited)
            }
            else -> return historyProgressFailure(FailureKind.Conflict)
        }
    return AppResult.Success(
        current.copy(pass = pass.copy(traversal = result, catalogDeferred = catalogDeferred))
    )
}

/**
 * Source progress is already validated by admitted operations; resetting positions alone is not
 * progress.
 */
private fun collaborationSpaceInboxHistoryVisitAdvanced(
    before: CollaborationSpaceInboxHistorySourceCheckpoint?,
    after: CollaborationSpaceInboxHistorySourceCheckpoint?,
): Boolean {
    if (after == null) return false
    if (before == null) return false
    if (
        before.version.generation != after.version.generation ||
            before.version.revision >= after.version.revision
    )
        return false
    val old = before.progress
    val new = after.progress
    return old.destinationGeneration != new.destinationGeneration ||
        old.passId != new.passId && old.historyComplete && old.pendingWindow?.complete == true ||
        !old.historyComplete && new.historyComplete ||
        new.historyCursor != null && new.historyCursor != old.historyCursor ||
        new.pendingWindow != null && new.pendingWindow != old.pendingWindow
}

/**
 * Only useful handoff or one newly coalesced trigger can justify another automatic finite round.
 */
private fun finishCollaborationSpaceInboxHistoryRound(
    current: CollaborationSpaceInboxHistoryProgress,
    round: CollaborationSpaceInboxHistoryTraversal.Sources,
): AppResult<CollaborationSpaceInboxHistoryProgress> {
    val pass = requireNotNull(current.pass)
    val consumeLater =
        round.unfinished && !round.advanced && pass.attemptedTrigger != current.latest
    val traversal =
        when {
            !round.unfinished -> CollaborationSpaceInboxHistoryTraversal.Ready
            round.advanced ->
                CollaborationSpaceInboxHistoryTraversal.Sources(
                    null,
                    null,
                    pass.catalogDeferred,
                    false,
                )
            consumeLater -> CollaborationSpaceInboxHistoryTraversal.Catalog(null, null)
            else -> CollaborationSpaceInboxHistoryTraversal.Parked
        }
    return AppResult.Success(
        current.copy(
            pass =
                pass.copy(
                    attemptedTrigger = if (consumeLater) current.latest else pass.attemptedTrigger,
                    traversal = traversal,
                    catalogDeferred = if (consumeLater) false else pass.catalogDeferred,
                )
        )
    )
}
