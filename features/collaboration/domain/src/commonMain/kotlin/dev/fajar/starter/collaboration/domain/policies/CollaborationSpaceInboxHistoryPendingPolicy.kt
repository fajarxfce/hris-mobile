package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** The retained physical checkpoint, not the latest raw history revision, bounds this lane. */
fun validCollaborationSpaceInboxHistoryPendingPage(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceInboxHistorySourceCheckpoint,
    limit: Int,
    page: CollaborationSpaceInboxHistoryPendingPage,
): Boolean {
    if (
        limit !in 1..16 ||
            !validCollaborationSpaceInboxHistorySourceCheckpoint(access, current) ||
            page.version != current.version ||
            page.references.size > limit
    )
        return false
    val expected =
        current.progress.pendingWindow
            ?: CollaborationSpaceInboxHistoryPendingWindow(
                current.version.generation,
                current.version.revision,
                null,
                false,
            )
    if (page.window != expected) return false
    val next = page.next
    if (
        next != null &&
            (next.length !in 1..4096 || next == expected.after || page.references.isEmpty())
    )
        return false
    if (expected.complete && (page.references.isNotEmpty() || next != null)) return false
    return page.references.map { it.eventId }.distinct().size == page.references.size &&
        page.references.zipWithNext().all { (a, b) ->
            a.insertedAtRevision <= b.insertedAtRevision
        } &&
        page.references.all {
            it.owner == current.progress.owner &&
                it.conversationId == current.progress.conversationId &&
                validCollaborationEventId(it.eventId) &&
                it.insertedAtRevision in 0..expected.throughRevision
        }
}

/** Every captured reference has exactly one checked outcome; raw traversal stays independent. */
fun collaborationSpaceInboxHistoryPendingHandoff(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceInboxHistorySourceCheckpoint,
    read: CollaborationSpaceInboxHistoryPendingPage,
    published: CollaborationSpaceInboxHistoryCommit,
): AppResult<CollaborationSpaceInboxHistorySourceMutation> {
    if (
        read.references.size !in 1..16 ||
            published.retainedEventIds.size > 16 ||
            published.unresolvedEventIds.size > 16
    )
        return invalidPendingHistory(FailureKind.Validation)
    val page = read.copy(references = read.references.toList())
    val retained = published.retainedEventIds.toList()
    val unresolved = published.unresolvedEventIds.toList()
    val outcomes = retained + unresolved
    val receipt = published.receipt
    if (
        !validCollaborationSpaceInboxHistoryPendingPage(access, current, 16, page) ||
            published.owner != current.progress.owner ||
            outcomes.distinct().size != outcomes.size ||
            outcomes.toSet() != page.references.map { it.eventId }.toSet() ||
            (receipt.source == null && retained.isNotEmpty()) ||
            !validCollaborationHistoryInboxReceipt(
                CollaborationHistoryInboxProposal(
                    current.progress.conversationId,
                    receipt.destination,
                    null,
                ),
                receipt.source != null,
                receipt,
            )
    )
        return invalidPendingHistory(FailureKind.Validation)
    return pendingHistoryMutation(current, page, receipt.destination, retained, unresolved)
}

/** Empty completion checks only actual destination metadata and never invents source evidence. */
fun collaborationSpaceInboxHistoryPendingCompletion(
    access: CollaborationSpaceLocalAccess,
    current: CollaborationSpaceInboxHistorySourceCheckpoint,
    read: CollaborationSpaceInboxHistoryPendingPage,
    destination: CollaborationSpaceInboxVersion?,
): AppResult<CollaborationSpaceInboxHistorySourceMutation> {
    if (
        read.references.isNotEmpty() ||
            read.next != null ||
            !validCollaborationSpaceInboxHistoryPendingPage(access, current, 16, read) ||
            !collaborationSpaceInboxHistoryDestinationCovers(destination, destination)
    )
        return invalidPendingHistory(FailureKind.Validation)
    return pendingHistoryMutation(current, read, destination, emptyList(), emptyList())
}

private fun pendingHistoryMutation(
    current: CollaborationSpaceInboxHistorySourceCheckpoint,
    page: CollaborationSpaceInboxHistoryPendingPage,
    destination: CollaborationSpaceInboxVersion?,
    retained: List<String>,
    unresolved: List<String>,
): AppResult<CollaborationSpaceInboxHistorySourceMutation> {
    if (current.progress.destinationGeneration != destination?.generation)
        return invalidPendingHistory(FailureKind.Conflict)
    val replacement =
        current.progress.copy(
            pendingWindow =
                page.window.copy(
                    after = page.next ?: page.window.after,
                    complete = page.next == null,
                )
        )
    if (
        current.version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER &&
            replacement != current.progress
    )
        return invalidPendingHistory(FailureKind.Conflict)
    return AppResult.Success(
        CollaborationSpaceInboxHistorySourceMutation(
            current,
            replacement,
            destination,
            retained,
            unresolved,
        )
    )
}

private fun invalidPendingHistory(kind: FailureKind) =
    collaborationRecoveryFailure(
        if (kind == FailureKind.Conflict) "client.collaboration_inbox_busy"
        else "client.collaboration_invalid_request",
        kind,
    )
