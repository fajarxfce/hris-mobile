package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*

/** Local command result only. Native display changes still require an exact render/owner check. */
sealed interface CollaborationNotificationCommandResult {
    val invocation: CollaborationNotificationInvocation

    data class ReplyRetained(
        override val invocation: CollaborationNotificationInvocation,
        val state: CollaborationMessageState,
    ) : CollaborationNotificationCommandResult {
        override fun toString() = "NotificationReplyRetained([redacted])"
    }

    data class ReadRetained(override val invocation: CollaborationNotificationInvocation) :
        CollaborationNotificationCommandResult {
        override fun toString() = "NotificationReadRetained([redacted])"
    }

    data class Muted(override val invocation: CollaborationNotificationInvocation) :
        CollaborationNotificationCommandResult {
        override fun toString() = "NotificationMuted([redacted])"
    }

    data class DismissRequested(override val invocation: CollaborationNotificationInvocation) :
        CollaborationNotificationCommandResult {
        override fun toString() = "NotificationDismissRequested([redacted])"
    }
}
