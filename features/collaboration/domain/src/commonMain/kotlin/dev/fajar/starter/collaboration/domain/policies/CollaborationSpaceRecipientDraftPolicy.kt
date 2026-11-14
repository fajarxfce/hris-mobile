package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceRecipientDraft(draft: CollaborationSpaceRecipientDraft): Boolean =
    validCollaborationSpaceConversationId(draft.peerAccountId) &&
        validCollaborationMessageId(draft.revision) &&
        validCollaborationDraftText(draft.text) &&
        draft.savedAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L

/** Structural coordinates only; current session and Group admission are checked by use cases. */
fun validCollaborationSpaceRecipientCoordinates(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.accountId) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        validCollaborationSpaceConversationId(peerAccountId) &&
        peerAccountId != access.accountId

/** Editing does not confer current peer eligibility or allocate a canonical conversation ID. */
fun validCollaborationSpaceRecipientDraftChange(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationSpaceRecipientDraft?,
    replacement: CollaborationSpaceRecipientDraft,
): Boolean =
    validCollaborationSpaceRecipientCoordinates(access, replacement.peerAccountId) &&
        validCollaborationSpaceRecipientDraft(replacement) &&
        (expected == null ||
            (validCollaborationSpaceRecipientDraft(expected) &&
                expected.peerAccountId == replacement.peerAccountId &&
                (expected.revision != replacement.revision || expected == replacement)))
