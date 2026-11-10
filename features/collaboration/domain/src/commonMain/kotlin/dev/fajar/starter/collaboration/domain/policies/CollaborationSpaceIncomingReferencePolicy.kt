package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Validates the complete immutable source attempt before either local progress write. */
fun planCollaborationSpaceIncomingReferences(
    access: CollaborationSpaceLocalAccess,
    key: CollaborationHistoryTraversal,
    source: CollaborationHistoryReferences,
    batch: CollaborationSpaceIncomingBatch?,
): AppResult<CollaborationSpaceIncomingReferencePlan> {
    val progress = source.progress ?: return invalidIncomingReferences()
    val position = progress.incoming ?: return invalidIncomingReferences()
    if (
        key.purpose != CollaborationHistoryPurpose.Incoming ||
            !validCollaborationHistoryTraversal(key) ||
            progress.provider == null ||
            !validCollaborationPendingHistoryReferences(source, position.pendingAfter, 16)
    )
        return invalidIncomingReferences()
    val through = position.pendingThrough ?: progress.references
    if (
        source.events.any { it.sequence >= through } ||
            (source.after != null && source.events.last().sequence + 1 >= through)
    )
        return invalidIncomingReferences()
    if (source.events.isEmpty()) {
        if (batch != null) return invalidIncomingReferences()
        return AppResult.Success(
            CollaborationSpaceIncomingReferencePlan(
                emptyList(),
                0,
                false,
                position.pendingRetryRequired,
            )
        )
    }
    if (
        batch == null ||
            batch.runtime.owner.localDirectoryAccess() != access ||
            !validCollaborationSpaceConversationId(batch.runtime.operationId) ||
            batch.runtime.deviceId != key.deviceId ||
            batch.conversationId != key.conversationId ||
            batch.roomId != key.roomId ||
            batch.provider != progress.provider ||
            batch.items.size != source.events.size ||
            batch.items.map { it.sourceEventId } != source.events.map { it.event.eventId }
    )
        return invalidIncomingReferences()
    val resolved = mutableListOf<CollaborationHistoryReference>()
    for ((reference, result) in source.events.zip(batch.items)) {
        when (val outcome = result.outcome) {
            is CollaborationIncomingHistoryOutcome.Retained -> {
                val ids = outcome.originalEventIds + outcome.replacementEventIds
                if (
                    ids.isEmpty() ||
                        ids.size > 32 ||
                        ids.any { !validCollaborationEventId(it) } ||
                        ids.distinct().size != ids.size ||
                        outcome.originalEventIds.size > 16 ||
                        outcome.replacementEventIds.size > 16
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

private fun invalidIncomingReferences() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_references_changed",
        FailureKind.Conflict,
    )
