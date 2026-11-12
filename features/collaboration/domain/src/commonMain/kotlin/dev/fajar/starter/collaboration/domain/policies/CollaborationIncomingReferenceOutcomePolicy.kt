package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Source-page and per-event retention policy shared by separately validated physical owners. */
internal fun planCollaborationIncomingReferenceOutcomes(
    source: CollaborationHistoryReferences,
    items: List<CollaborationIncomingHistoryResult>?,
): AppResult<CollaborationSpaceIncomingReferencePlan> {
    val progress = source.progress ?: return invalidIncomingReferences()
    val position = progress.incoming ?: return invalidIncomingReferences()
    if (!validCollaborationPendingHistoryReferences(source, position.pendingAfter, 16))
        return invalidIncomingReferences()
    val through = position.pendingThrough ?: progress.references
    if (
        source.events.any { it.sequence >= through } ||
            (source.after != null && source.events.last().sequence + 1 >= through)
    )
        return invalidIncomingReferences()
    if (source.events.isEmpty()) {
        if (items != null) return invalidIncomingReferences()
        return AppResult.Success(
            CollaborationSpaceIncomingReferencePlan(
                emptyList(),
                0,
                false,
                position.pendingRetryRequired,
            )
        )
    }
    if (items == null) return invalidIncomingReferences()
    val planned = planCollaborationHistoryReferenceResolutions(source.events, items)
    if (planned is AppResult.Failed) return planned
    val resolved = (planned as AppResult.Success).value
    val unresolved = source.events.size - resolved.size
    return AppResult.Success(
        CollaborationSpaceIncomingReferencePlan(
            resolved.toList(),
            unresolved,
            source.after != null,
            position.pendingRetryRequired || unresolved > 0,
        )
    )
}

/** Per-event outcomes only; each caller validates its own position and admission separately. */
internal fun planCollaborationHistoryReferenceResolutions(
    references: List<CollaborationHistoryReference>,
    items: List<CollaborationIncomingHistoryResult>,
): AppResult<List<CollaborationHistoryReference>> {
    if (
        references.size > 16 ||
            items.size != references.size ||
            items.map { it.sourceEventId } != references.map { it.event.eventId }
    )
        return invalidIncomingReferences()
    val resolved = mutableListOf<CollaborationHistoryReference>()
    for ((reference, result) in references.zip(items)) {
        when (val outcome = result.outcome) {
            is CollaborationIncomingHistoryOutcome.Retained -> {
                if (outcome.originalEventIds.size > 16 || outcome.replacementEventIds.size > 16)
                    return invalidIncomingReferences()
                val ids = outcome.originalEventIds + outcome.replacementEventIds
                if (
                    ids.isEmpty() ||
                        ids.size > 32 ||
                        ids.any { !validCollaborationEventId(it) } ||
                        ids.distinct().size != ids.size
                )
                    return invalidIncomingReferences()
                resolved += reference
            }
            CollaborationIncomingHistoryOutcome.Unsupported -> resolved += reference
            is CollaborationIncomingHistoryOutcome.AwaitDecryption ->
                if (!validCollaborationEventId(outcome.eventId)) return invalidIncomingReferences()
            is CollaborationIncomingHistoryOutcome.AwaitRedaction ->
                if (!validCollaborationEventId(outcome.eventId)) return invalidIncomingReferences()
            is CollaborationIncomingHistoryOutcome.MissingOriginal ->
                if (!validCollaborationEventId(outcome.eventId)) return invalidIncomingReferences()
        }
    }
    return AppResult.Success(resolved.toList())
}

private fun invalidIncomingReferences() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_references_changed",
        FailureKind.Conflict,
    )
