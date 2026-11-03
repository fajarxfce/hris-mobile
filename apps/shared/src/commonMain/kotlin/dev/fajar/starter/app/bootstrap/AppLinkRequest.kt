package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.navigation.AppLink
import dev.fajar.starter.notifications.data.dto.ActionNotificationInput
import dev.fajar.starter.workspace.domain.entities.WorkspaceSelectionOwner

/** One latest request in the ViewModel lifetime, including while bootstrap is pending. */
internal sealed interface AppLinkRequest {
    val sequence: Long

    data class External(override val sequence: Long, val target: AppLink) : AppLinkRequest

    data class Notification(
        override val sequence: Long,
        val input: ActionNotificationInput,
        val owner: WorkspaceSelectionOwner?,
    ) : AppLinkRequest {
        override fun toString() = "NotificationRequest([redacted])"
    }
}
