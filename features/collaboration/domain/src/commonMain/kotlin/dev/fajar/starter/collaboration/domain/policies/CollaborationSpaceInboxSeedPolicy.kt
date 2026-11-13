package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Rebuild one retained original without inventing a send, editor revision or a newer timestamp. */
fun collaborationSpaceInboxSeedPublication(
    access: CollaborationSpaceLocalAccess,
    conversation: CollaborationSpaceConversationMetadata,
    record: CollaborationSpaceMessageRecord,
    current: CollaborationSpaceInboxConversation,
    anchors: CollaborationSpaceInboxActivities,
): AppResult<CollaborationSpaceInboxPublication> {
    val intent = record.intent
    if (
        !validCollaborationSpaceMessageRecord(access, record) ||
            intent.editTarget != null ||
            !validCollaborationSpaceConversationMetadata(access, conversation) ||
            conversation.id != intent.conversationId
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val captured = captureCollaborationSpaceInboxConversation(conversation.id, current)
    if (captured is AppResult.Failed) return captured
    val owned = (captured as AppResult.Success).value
    if (anchors.version != owned.version)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val found = captureCollaborationSpaceInboxComposerAnchor(intent.id, anchors)
    if (found is AppResult.Failed) return found
    val old = (found as AppResult.Success).value
    val source = CollaborationSpaceInboxSource.GroupOriginal(intent.id)
    val candidate = owned.candidates.singleOrNull { it.source == source }
    val createdAt = conversation.createdAt.toEpochMilliseconds()
    if (
        (old != null && owned.entry == null) ||
            (candidate != null && candidate != old) ||
            (owned.entry?.newest?.source == source && owned.entry.newest != old) ||
            createdAt !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            (owned.entry != null && owned.entry.createdAt != createdAt)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val allocated = if (old == null) 1 else 0
    val previousOrdinal = owned.version?.lastOrdinal ?: 0L
    if (previousOrdinal > COLLABORATION_SPACE_INBOX_MAX_ORDER - allocated)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val lastOrdinal = previousOrdinal + allocated
    // A retained anchor can be edited, hidden or already confirmed. Its independent state wins.
    val activity =
        old
            ?: CollaborationSpaceInboxActivity(
                source,
                CollaborationSpaceInboxPosition(
                    maxOf(createdAt, intent.createdAt.toEpochMilliseconds()),
                    lastOrdinal,
                ),
                if (record.state == CollaborationMessageState.Cancelled) null
                else
                    CollaborationSpaceInboxPreview(
                        CollaborationSpaceInboxContent.TEXT,
                        intent.text,
                        intent.createdAt.toEpochMilliseconds(),
                        if (record.state is CollaborationMessageState.Sent)
                            CollaborationMessageDelivery.SENT
                        else CollaborationMessageDelivery.PENDING,
                    ),
            )
    val newest =
        (owned.candidates.filter { it.source != source } +
                listOfNotNull(activity.takeIf { it.preview != null }))
            .maxWithOrNull { a, b ->
                compareCollaborationSpaceInboxPositions(a.position, b.position)
            }
    val proposal =
        CollaborationSpaceInboxPublication(
            owned.version,
            lastOrdinal,
            collaborationSpaceInboxEntry(conversation.id, createdAt, owned.draft, newest),
            activities = listOf(activity),
            repair = CollaborationSpaceInboxRepairChange.Retain(intent),
        )
    return if (validCollaborationSpaceInboxPublication(proposal)) AppResult.Success(proposal)
    else
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
