package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.notifications.data.dto.*

/** IDs from owned acquisition, never preview text taken from a push. */
data class CollaborationNotificationCandidate(
    val companyId: String,
    val conversationId: String,
    val roomId: String,
    val eventId: String,
) {
    override fun toString() = "CollaborationNotificationCandidate([redacted])"
}

/** Generic localized text only. Acquired sender/message text comes from admitted preparation. */
data class CollaborationNotificationCopy(val title: String, val body: String)

enum class CollaborationNotificationPublicationResult {
    Submitted,
    PendingHistory,
    Suppressed,
    Unchanged,
    PermissionRequired,
    ChannelDisabled,
    CapacityReached,
}

internal fun CollaborationNotificationPublication.nativePayload(
    copy: CollaborationNotificationCopy,
    silent: Boolean,
): ActionNotificationPayload {
    val message = requireNotNull(body.message)
    val actions = requireNotNull(body.actions)
    return ActionNotificationPayload(
        reference = ActionNotificationReference(body.target.notificationId, body.target.renderId),
        title =
            when (message.preview) {
                CollaborationNotificationPreview.Hidden -> copy.title
                CollaborationNotificationPreview.SenderOnly,
                CollaborationNotificationPreview.Message -> message.senderName ?: copy.title
            },
        body =
            when (message.preview) {
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationPreview.SenderOnly -> copy.body
                CollaborationNotificationPreview.Message -> message.body ?: copy.body
            },
        envelopes =
            mapOf(
                // Open is a navigation-only alias for the authenticated Reply target. The native
                // host must authenticate/match the target and must never dispatch a command for
                // Open.
                ActionNotificationKind.Open to actions.reply.encoded,
                ActionNotificationKind.Reply to actions.reply.encoded,
                ActionNotificationKind.MarkRead to actions.markRead.encoded,
                ActionNotificationKind.Mute to actions.mute.encoded,
                ActionNotificationKind.Dismiss to actions.dismiss.encoded,
            ),
        silent = silent,
    )
}
