package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/** Presentation continuity only. The source outbox remains the durable authority. */
data class RecipientComposerEditingState(
    val ready: Boolean = false,
    val text: String = "",
    val saving: Boolean = false,
    val failure: Failure? = null,
    val submissions: List<RecipientComposerPendingSubmission> = emptyList(),
) {
    override fun toString() = "RecipientComposerEditingState([redacted])"
}

data class RecipientComposerPendingSubmission(
    val original: CollaborationSpaceRecipientSubmission,
    val durable: Boolean = false,
    val failure: Failure? = null,
) {
    override fun toString() = "RecipientComposerPendingSubmission([redacted])"
}

internal data class RecipientComposerDraftWrite(
    val access: CollaborationSpaceLocalAccess,
    val expected: CollaborationSpaceRecipientDraft?,
    val replacement: CollaborationSpaceRecipientDraft,
) {
    override fun toString() = "RecipientComposerDraftWrite([redacted])"
}

internal data class RecipientComposerSubmissionWrite(
    val access: CollaborationSpaceLocalAccess,
    val expected: CollaborationSpaceRecipientDraft?,
    val original: CollaborationSpaceRecipientSubmission,
) {
    override fun toString() = "RecipientComposerSubmissionWrite([redacted])"
}
