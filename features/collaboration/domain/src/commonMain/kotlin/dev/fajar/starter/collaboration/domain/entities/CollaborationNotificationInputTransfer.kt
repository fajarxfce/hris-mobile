package dev.fajar.starter.collaboration.domain.entities

/** Local copy/cleanup only. An owned reply still needs independent review and send admission. */
sealed interface CollaborationNotificationInputTransfer {
    data object Absent : CollaborationNotificationInputTransfer

    data class Transferred(val replyId: String) : CollaborationNotificationInputTransfer {
        override fun toString() = "NotificationInputTransferred([redacted])"
    }
}
