package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** An immutable local send gesture, separate from subsequent text in the composer. */
data class CollaborationComposerSubmission(
    val original: CollaborationDraft,
    val nextRevision: String,
    val submittedAt: Instant,
) {
    val id: String
        get() = original.revision

    val nextDraft: CollaborationDraft
        get() =
            original.copy(
                revision = nextRevision,
                text = "",
                savedAt = submittedAt,
                replyToEventId = null,
            )

    override fun toString() = "CollaborationComposerSubmission([redacted])"
}

data class CollaborationComposerSubmissionPage(
    val submissions: List<CollaborationComposerSubmission>,
    val next: String?,
) {
    override fun toString() = "CollaborationComposerSubmissionPage([redacted])"
}

enum class CollaborationComposerHandoffResult {
    STORED,
    RETAINED,
    CHANGED,
    CAPACITY_EXCEEDED,
}
