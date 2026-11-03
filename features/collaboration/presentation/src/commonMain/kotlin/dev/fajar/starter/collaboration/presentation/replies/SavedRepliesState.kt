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
    val sendOutcome: CollaborationReviewedReplyResult? = null,
    val draft: String? = null,
    val savingDraft: Boolean = false,
    val draftFailure: Failure? = null,
    val pendingSendId: String? = null,
    val discardMode: CollaborationReplyDiscardMode = CollaborationReplyDiscardMode.All,
    val leaveConfirmation: Boolean = false,
) {
    val selected: CollaborationSavedReply?
        get() = snapshot?.items?.find { it.input.id == replyId }

    val editable: Boolean
        get() =
            selected?.input?.let { it.review != null && it.resolution == null } == true &&
                pendingSendId == null &&
                !busy &&
                !confirmation &&
                failure == null

    val unsavedDraft: Boolean
        get() =
            savingDraft ||
                draftFailure != null ||
                (draft != null &&
                    selected?.input?.let {
                        it.resolution == null && draft != (it.draft ?: it.candidate.text)
                    } == true)

    override fun toString() = "SavedRepliesState([redacted])"
}
