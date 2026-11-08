package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerSubmission
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageRecord

/** Reference identity also fences a superseded dialog reopened for the same logical message. */
internal class ChatCancellationSelection(
    val access: CollaborationLocalAccess,
    val record: CollaborationMessageRecord? = null,
    val original: CollaborationComposerSubmission? = null,
) {
    override fun toString() = "ChatCancellationSelection([redacted])"
}
