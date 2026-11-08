package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationComposerSubmission(submission: CollaborationComposerSubmission): Boolean =
    validCollaborationDraft(submission.original) &&
        submission.original.submissionId == null &&
        validCollaborationText(submission.original.text) &&
        validCollaborationMessageId(submission.nextRevision) &&
        setOf(submission.id, submission.original.conversationId, submission.nextRevision).size ==
            3 &&
        submission.submittedAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L

fun validCollaborationComposerHandoff(
    submission: CollaborationComposerSubmission,
    expected: CollaborationDraft?,
): Boolean =
    validCollaborationComposerSubmission(submission) &&
        (expected == null ||
            (validCollaborationDraft(expected) &&
                expected.conversationId == submission.original.conversationId &&
                expected.submissionId == null))

/** Recovery preserves the gesture's identity and ordering, independently of when it is visited. */
fun collaborationComposerIntent(
    access: CollaborationLocalAccess,
    submission: CollaborationComposerSubmission,
) =
    CollaborationMessageIntent(
        submission.id,
        access.messageOwner(),
        submission.original.conversationId,
        submission.submittedAt,
        submission.original.text,
        submission.original.replyToEventId,
    )
