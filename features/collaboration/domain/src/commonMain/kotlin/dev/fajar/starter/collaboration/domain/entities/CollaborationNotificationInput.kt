package dev.fajar.starter.collaboration.domain.entities

/** Private device input, not authenticated account ownership, company content or a send grant. */
data class CollaborationUnverifiedNotificationReply(
    val token: CollaborationNotificationActionToken,
    val text: String,
) {
    override fun toString() = "CollaborationUnverifiedNotificationReply([redacted])"
}

data class CollaborationNotificationInputReference(val id: String, val generation: String) {
    override fun toString() = "CollaborationNotificationInputReference([redacted])"
}

data class CollaborationNotificationInput(
    val reference: CollaborationNotificationInputReference,
    val reply: CollaborationUnverifiedNotificationReply,
) {
    override fun toString() = "CollaborationNotificationInput([redacted])"
}

sealed interface CollaborationNotificationInputRetention {
    data class Retained(val input: CollaborationNotificationInput) :
        CollaborationNotificationInputRetention {
        override fun toString() = "NotificationInputRetained([redacted])"
    }

    data object CapacityExceeded : CollaborationNotificationInputRetention

    data object Contended : CollaborationNotificationInputRetention
}
