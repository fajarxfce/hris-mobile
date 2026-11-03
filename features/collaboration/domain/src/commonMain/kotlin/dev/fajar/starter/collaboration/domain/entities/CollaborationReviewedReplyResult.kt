package dev.fajar.starter.collaboration.domain.entities

sealed interface CollaborationReviewedReplyResult {
    data object Absent : CollaborationReviewedReplyResult

    data class NeedsReview(val reason: CollaborationNotificationReplyReview) :
        CollaborationReviewedReplyResult

    data class CommandRetained(val state: CollaborationMessageState) :
        CollaborationReviewedReplyResult {
        override fun toString() = "CommandRetained([redacted])"
    }
}
