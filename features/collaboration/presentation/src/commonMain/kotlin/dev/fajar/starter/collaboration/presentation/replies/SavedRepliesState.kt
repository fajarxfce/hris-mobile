package dev.fajar.starter.collaboration.presentation.replies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

data class SavedRepliesState(
    val replyId: String = "",
    val snapshot: CollaborationSavedReplies? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val confirmation: Boolean = false,
    val failure: Failure? = null,
    val actionFailure: Failure? = null,
    val outcome: CollaborationReplyDiscard? = null,
) {
    val selected: CollaborationSavedReply?
        get() = snapshot?.items?.find { it.input.id == replyId }

    override fun toString() = "SavedRepliesState([redacted])"
}
