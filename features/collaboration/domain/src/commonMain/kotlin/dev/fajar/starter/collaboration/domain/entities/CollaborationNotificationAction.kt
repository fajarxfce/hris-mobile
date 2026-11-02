package dev.fajar.starter.collaboration.domain.entities

/** Opaque native IPC envelope; never includes reply text or a transport credential. */
data class CollaborationNotificationActionToken(val encoded: String) {
    override fun toString() = "CollaborationNotificationActionToken([redacted])"
}

data class CollaborationNotificationActionTarget(
    val companyId: String,
    val conversationId: String,
    val roomId: String,
    val eventId: String,
    val notificationId: String,
    val renderId: String,
) {
    override fun toString() = "CollaborationNotificationActionTarget([redacted])"
}

/** Decoding proves the issuing native family, not current company/history or command admission. */
sealed interface CollaborationNotificationAction {
    val operationId: String
    val target: CollaborationNotificationActionTarget

    data class Reply(
        override val operationId: String,
        override val target: CollaborationNotificationActionTarget,
    ) : CollaborationNotificationAction {
        override fun toString() = "NotificationReplyAction([redacted])"
    }

    data class MarkRead(
        override val operationId: String,
        override val target: CollaborationNotificationActionTarget,
    ) : CollaborationNotificationAction {
        override fun toString() = "NotificationReadAction([redacted])"
    }

    data class Mute(
        override val operationId: String,
        override val target: CollaborationNotificationActionTarget,
        val expected: CollaborationNotificationSettingsRevision?,
        val preview: CollaborationNotificationPreview,
    ) : CollaborationNotificationAction {
        override fun toString() = "NotificationMuteAction([redacted])"
    }

    data class Dismiss(
        override val operationId: String,
        override val target: CollaborationNotificationActionTarget,
    ) : CollaborationNotificationAction {
        override fun toString() = "NotificationDismissAction([redacted])"
    }
}
