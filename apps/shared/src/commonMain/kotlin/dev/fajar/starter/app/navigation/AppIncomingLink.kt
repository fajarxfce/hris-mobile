package dev.fajar.starter.app.navigation

import dev.fajar.starter.notifications.data.dto.ActionNotificationInput

/** Platform input stays untrusted until the owning resolver authenticates it. */
sealed interface AppIncomingLink {
    data class ExternalUri(val uri: String) : AppIncomingLink {
        override fun toString() = "ExternalUri([redacted])"
    }

    data class Notification(val input: ActionNotificationInput) : AppIncomingLink {
        override fun toString() = "NotificationLink([redacted])"
    }
}
