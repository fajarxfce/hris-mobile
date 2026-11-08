package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerSubmission

fun validCollaborationComposerSubmission(submission: CollaborationComposerSubmission): Boolean =
    validCollaborationDraft(submission.original) &&
        submission.original.submissionId == null &&
        validCollaborationText(submission.original.text) &&
        validCollaborationMessageId(submission.nextRevision) &&
        setOf(submission.id, submission.original.conversationId, submission.nextRevision).size ==
            3 &&
        submission.submittedAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L
