package dev.fajar.starter.collaboration.domain.entities

/** Local input retirement only; neither provider delivery nor permission to change native UI. */
sealed interface CollaborationNotificationReplyCompletion {
    /** No input currently occupies this ID. This does not prove a message was sent or retained. */
    data object Absent : CollaborationNotificationReplyCompletion

    /** No matching operation exists yet. This result grants no send or review decision. */
    data object Pending : CollaborationNotificationReplyCompletion

    data class Retired(val state: CollaborationMessageState) :
        CollaborationNotificationReplyCompletion {
        override fun toString() = "Retired([redacted])"
    }
}
