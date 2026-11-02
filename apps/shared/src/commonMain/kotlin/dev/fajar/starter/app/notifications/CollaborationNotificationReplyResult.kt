package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageState

/** Local outcomes only; none grants native notification publication, cancellation or delivery. */
sealed interface CollaborationNotificationReplyResult {
    /** No retained input is present. This does not prove the message exists or was delivered. */
    data object Absent : CollaborationNotificationReplyResult

    /** The exact command was reproduced before its input was retired. */
    data class CommandRetained(val state: CollaborationMessageState) :
        CollaborationNotificationReplyResult {
        override fun toString() = "NotificationReplyCommandRetained([redacted])"
    }

    /** The owned input remains encrypted for explicit review, with its original text and token. */
    data class NeedsReview(val inputId: String) : CollaborationNotificationReplyResult {
        override fun toString() = "NotificationReplyNeedsReview([redacted])"
    }
}
