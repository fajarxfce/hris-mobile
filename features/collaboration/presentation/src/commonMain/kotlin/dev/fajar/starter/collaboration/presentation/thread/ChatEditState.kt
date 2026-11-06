package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.CollaborationEditCandidate
import dev.fajar.starter.collaboration.domain.policies.validCollaborationText
import dev.fajar.starter.common.result.Failure

data class ChatEditState(
    val candidate: CollaborationEditCandidate,
    val text: String,
    val busy: Boolean = false,
    /** A lost local write response must retry the same immutable command. */
    val locked: Boolean = false,
    val failure: Failure? = null,
) {
    val canSave: Boolean
        get() = !busy && validCollaborationText(text) && text != candidate.text

    override fun toString() = "ChatEditState([redacted])"
}
