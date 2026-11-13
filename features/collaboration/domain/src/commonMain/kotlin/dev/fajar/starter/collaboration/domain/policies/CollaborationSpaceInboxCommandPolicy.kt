package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Freeze exact original command evidence before deciding whether an index read is needed. */
fun captureCollaborationSpaceInboxCommand(
    access: CollaborationSpaceLocalAccess,
    reference: CollaborationSpaceInboxRepairReference,
    record: CollaborationSpaceMessageRecord?,
): AppResult<CollaborationSpaceMessageRecord?> {
    if (
        !validCollaborationSpaceInboxRepairReference(reference) ||
            !validCollaborationSpaceMessageIntent(access, reference.intent)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val state = record?.state
    val frozen =
        if (state is CollaborationMessageState.Staging)
            record.copy(state = state.copy(before = state.before.toSet()))
        else record
    if (
        frozen != null &&
            (!validCollaborationSpaceMessageRecord(access, frozen) ||
                frozen.intent != reference.intent)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(frozen)
}

/**
 * Terminal progress changes only receipt/hiding; current body, timestamp and editor stay intact.
 */
fun collaborationSpaceInboxCommandPublication(
    access: CollaborationSpaceLocalAccess,
    reference: CollaborationSpaceInboxRepairReference,
    record: CollaborationSpaceMessageRecord,
    current: CollaborationSpaceInboxConversation,
    anchors: CollaborationSpaceInboxActivities,
): AppResult<CollaborationSpaceInboxPublication> {
    val checked = captureCollaborationSpaceInboxCommand(access, reference, record)
    if (checked is AppResult.Failed) return checked
    val state = requireNotNull((checked as AppResult.Success).value).state
    if (state !is CollaborationMessageState.Sent && state != CollaborationMessageState.Cancelled)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val conversationId = reference.intent.conversationId
    val capture = captureCollaborationSpaceInboxConversation(conversationId, current)
    if (capture is AppResult.Failed) return capture
    val owned = (capture as AppResult.Success).value
    if (
        owned.version == null ||
            owned.entry == null ||
            reference.insertedAtRevision > owned.version.revision
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    if (anchors.version != owned.version)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val found = captureCollaborationSpaceInboxComposerAnchor(reference.intent.id, anchors)
    if (found is AppResult.Failed) return found
    val anchor =
        (found as AppResult.Success).value
            ?: return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
    val candidate = owned.candidates.singleOrNull { it.source == anchor.source }
    if (
        (candidate != null && candidate != anchor) ||
            (owned.entry.newest?.source == anchor.source && owned.entry.newest != anchor)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    if (
        state == CollaborationMessageState.Cancelled &&
            anchor.preview?.delivery == CollaborationMessageDelivery.SENT
    )
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val changed =
        anchor.copy(
            preview =
                when (state) {
                    is CollaborationMessageState.Sent ->
                        anchor.preview?.copy(delivery = CollaborationMessageDelivery.SENT)
                    else -> null
                }
        )
    val newest =
        (owned.candidates.filter { it.source != anchor.source } +
                listOfNotNull(changed.takeIf { it.preview != null }))
            .maxWithOrNull { a, b ->
                compareCollaborationSpaceInboxPositions(a.position, b.position)
            }
    val publication =
        CollaborationSpaceInboxPublication(
            owned.version,
            owned.version.lastOrdinal,
            collaborationSpaceInboxEntry(
                conversationId,
                owned.entry.createdAt,
                owned.draft,
                newest,
            ),
            activities = listOf(changed),
            repair = CollaborationSpaceInboxRepairChange.Complete(reference),
        )
    return if (validCollaborationSpaceInboxPublication(publication)) AppResult.Success(publication)
    else
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
