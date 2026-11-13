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

/** Validate and detach one bounded indexed page before orchestration reads its continuation. */
fun captureCollaborationSpaceComposerPage(
    access: CollaborationSpaceLocalAccess,
    value: CollaborationComposerSubmissionPage,
    after: String? = null,
    limit: Int = 8,
): CollaborationComposerSubmissionPage? {
    if (limit !in 1..8 || (after != null && !validCollaborationMessageId(after))) return null
    val originals = value.submissions.toList()
    if (
        originals.size > limit ||
            originals.any {
                !validCollaborationComposerSubmission(it) ||
                    !validCollaborationSpaceMessageIntent(
                        access,
                        collaborationSpaceComposerIntent(access, it),
                    ) ||
                    (after != null && it.id <= after)
            } ||
            originals.map { it.id } != originals.map { it.id }.distinct().sorted() ||
            (value.next != null && (originals.size != limit || value.next != originals.last().id))
    )
        return null
    return value.copy(submissions = originals)
}
