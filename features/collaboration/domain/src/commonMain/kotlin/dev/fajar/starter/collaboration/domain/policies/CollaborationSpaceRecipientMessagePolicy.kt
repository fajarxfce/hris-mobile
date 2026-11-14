package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Canonical metadata must name the same two accounts captured by this local submission. */
fun validCollaborationSpaceRecipientConversation(
    access: CollaborationSpaceLocalAccess,
    submission: CollaborationSpaceRecipientSubmission,
    conversation: CollaborationSpaceConversationMetadata,
): Boolean =
    validCollaborationSpaceRecipientSubmission(submission) &&
        validCollaborationSpaceRecipientCoordinates(access, submission.original.peerAccountId) &&
        validCollaborationSpaceConversationMetadata(access, conversation) &&
        setOf(conversation.firstAccountId, conversation.secondAccountId) ==
            setOf(access.accountId, submission.original.peerAccountId)

/** Old canonical originals have no recipient provenance; absence never proves recipient replay. */
fun validCollaborationSpaceRecipientOrigin(intent: CollaborationSpaceMessageIntent): Boolean {
    val origin = intent.recipientOrigin ?: return true
    return intent.editTarget == null &&
        intent.inboxOrigin == null &&
        intent.replyToEventId == null &&
        !intent.forwarded &&
        origin.peerAccountId != intent.owner.accountId &&
        validCollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientSubmission(
                CollaborationSpaceRecipientDraft(
                    origin.peerAccountId,
                    intent.id,
                    intent.text,
                    origin.draftSavedAt,
                ),
                origin.nextRevision,
                intent.createdAt,
            )
        )
}

/** Conversion preserves the gesture; the caller must separately prove canonical target facts. */
fun collaborationSpaceRecipientMessageIntent(
    access: CollaborationSpaceLocalAccess,
    submission: CollaborationSpaceRecipientSubmission,
    conversationId: String,
) =
    CollaborationSpaceMessageIntent(
        submission.id,
        access.messageOwner(),
        conversationId,
        submission.submittedAt,
        submission.original.text,
        recipientOrigin =
            CollaborationSpaceRecipientOrigin(
                submission.original.peerAccountId,
                submission.original.savedAt,
                submission.nextRevision,
            ),
    )

/** Exact retained original, including cancellation/completion; this is not a delivery receipt. */
fun matchesCollaborationSpaceRecipientSubmission(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
    submission: CollaborationSpaceRecipientSubmission,
): Boolean =
    validCollaborationSpaceRecipientSubmission(submission) &&
        validCollaborationSpaceMessageRecord(access, record) &&
        record.intent ==
            collaborationSpaceRecipientMessageIntent(
                access,
                submission,
                record.intent.conversationId,
            )
