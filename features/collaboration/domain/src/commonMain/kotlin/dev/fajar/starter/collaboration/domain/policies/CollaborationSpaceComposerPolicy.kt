package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Editing and submission preserve their full captured values; age is not local ownership. */
fun validCollaborationSpaceDraftChange(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationDraft?,
    replacement: CollaborationDraft,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.accountId) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        validCollaborationDraft(replacement) &&
        replacement.submissionId == null &&
        (expected == null ||
            (validCollaborationDraft(expected) &&
                expected.submissionId == null &&
                expected.conversationId == replacement.conversationId &&
                (expected.revision != replacement.revision || expected == replacement)))

/** Pure conversion; delivery and live admission remain outside the captured local gesture. */
fun collaborationSpaceComposerIntent(
    access: CollaborationSpaceLocalAccess,
    submission: CollaborationComposerSubmission,
) =
    CollaborationSpaceMessageIntent(
        submission.id,
        access.messageOwner(),
        submission.original.conversationId,
        submission.submittedAt,
        submission.original.text,
        submission.original.replyToEventId,
    )
