package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Advance only from a checked publication covering this frozen source interval. Unresolved IDs
 * accompany the cursor so storage can retain both atomically; acquisition alone cannot advance.
 */
fun collaborationSpaceInboxHistoryHandoff(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceInboxHistorySourceCheckpoint,
    read: CollaborationSpaceHistoryChangesRead,
    published: CollaborationSpaceInboxHistoryCommit,
): AppResult<CollaborationSpaceInboxHistoryHandoff> {
    if (!validCollaborationSpaceInboxHistorySourceCheckpoint(access, current))
        return invalidHistoryHandoff(FailureKind.Validation)
    val source = current.progress
    if (source.historyComplete || read.changes == CollaborationHistoryChanges.Restart)
        return invalidHistoryHandoff(FailureKind.Conflict)
    if (
        (read.changes as? CollaborationHistoryChanges.Page)?.eventIds?.size?.let { it > 16 } ==
            true || published.retainedEventIds.size > 16 || published.unresolvedEventIds.size > 16
    )
        return invalidHistoryHandoff(FailureKind.Validation)
    val changes =
        when (val observed = read.changes) {
            is CollaborationHistoryChanges.Page ->
                observed.copy(eventIds = observed.eventIds.toList())
            else -> observed
        }
    val retained = published.retainedEventIds.toList()
    val unresolved = published.unresolvedEventIds.toList()
    if (
        source.owner != read.owner ||
            published.owner != source.owner ||
            !validCollaborationHistoryChanges(source.owner, source.historyCursor, 16, changes) ||
            !validHistoryHandoffReferences(changes, retained, unresolved)
    )
        return invalidHistoryHandoff(FailureKind.Validation)
    val receipt = published.receipt
    val page = changes as? CollaborationHistoryChanges.Page
    val version = receipt.source
    if (
        !validCollaborationHistoryInboxReceipt(
            CollaborationHistoryInboxProposal(source.conversationId, receipt.destination, null),
            page != null,
            receipt,
        ) ||
            (page != null &&
                (version == null ||
                    version.generation != page.window.generation ||
                    version.revision < page.window.throughRevision))
    )
        return invalidHistoryHandoff(FailureKind.Validation)
    val destination = receipt.destination?.generation
    if (
        source.destinationGeneration != destination &&
            !(source.destinationGeneration == null && source.historyCursor == null)
    )
        return invalidHistoryHandoff(FailureKind.Conflict)
    val cursor =
        page?.let {
            it.next
                ?: CollaborationHistoryChangeCursor.After(
                    it.window.owner,
                    it.window.generation,
                    it.window.throughRevision,
                )
        }
    val replacement =
        source.copy(
            destinationGeneration = destination,
            historyCursor = cursor,
            historyComplete = page?.next == null,
        )
    return AppResult.Success(
        CollaborationSpaceInboxHistoryHandoff(current, replacement, receipt, retained, unresolved)
    )
}

private fun validHistoryHandoffReferences(
    changes: CollaborationHistoryChanges,
    retained: List<String>,
    unresolved: List<String>,
): Boolean {
    val references = (changes as? CollaborationHistoryChanges.Page)?.eventIds.orEmpty()
    if (retained.size + unresolved.size != references.size) return false
    val outcomes = retained + unresolved
    return outcomes.distinct().size == outcomes.size && outcomes.toSet() == references.toSet()
}

private fun invalidHistoryHandoff(kind: FailureKind) =
    collaborationRecoveryFailure(
        if (kind == FailureKind.Conflict) "client.collaboration_inbox_busy"
        else "client.collaboration_invalid_request",
        kind,
    )
