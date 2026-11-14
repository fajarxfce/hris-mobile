package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** One explicit Send before canonical conversation setup, independent of later typing. */
data class CollaborationSpaceRecipientSubmission(
    val original: CollaborationSpaceRecipientDraft,
    val nextRevision: String,
    val submittedAt: Instant,
) {
    val id: String
        get() = original.revision

    val nextDraft: CollaborationSpaceRecipientDraft
        get() = original.copy(revision = nextRevision, text = "", savedAt = submittedAt)

    override fun toString() = "CollaborationSpaceRecipientSubmission([redacted])"
}

data class CollaborationSpaceRecipientSubmissionPage(
    val submissions: List<CollaborationSpaceRecipientSubmission>,
    val next: String?,
) {
    override fun toString() = "CollaborationSpaceRecipientSubmissionPage([redacted])"
}
