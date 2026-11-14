package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceRecipientSubmission(
    value: CollaborationSpaceRecipientSubmission
): Boolean =
    validCollaborationSpaceRecipientDraft(value.original) &&
        validCollaborationText(value.original.text) &&
        validCollaborationMessageId(value.nextRevision) &&
        setOf(value.id, value.nextRevision, value.original.peerAccountId).size == 3 &&
        value.submittedAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L

fun validCollaborationSpaceRecipientHandoff(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationSpaceRecipientDraft?,
    value: CollaborationSpaceRecipientSubmission,
): Boolean =
    validCollaborationSpaceRecipientSubmission(value) &&
        validCollaborationSpaceRecipientDraftChange(access, expected, value.original) &&
        expected?.revision != value.nextRevision
