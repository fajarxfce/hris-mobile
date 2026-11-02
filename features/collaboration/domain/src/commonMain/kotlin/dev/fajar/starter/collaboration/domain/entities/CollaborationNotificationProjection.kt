package dev.fajar.starter.collaboration.domain.entities

enum class CollaborationNotificationSuppression {
    Muted,
    Read,
    OwnMessage,
    Redacted,
}

/**
 * Local preparation only; the delivery coordinator still owns admission, deduplication and display.
 */
sealed interface CollaborationNotificationProjection {
    data object PendingHistory : CollaborationNotificationProjection

    data class Suppressed(val reason: CollaborationNotificationSuppression) :
        CollaborationNotificationProjection

    data class Ready(val message: CollaborationMessageNotification) :
        CollaborationNotificationProjection
}

/**
 * Preview-limited content and immutable source stamps, never a grant to display for another owner.
 */
data class CollaborationMessageNotification(
    val access: CollaborationLocalAccess,
    val conversationId: String,
    val roomId: String,
    val eventId: String,
    val contentHash: String,
    val settingsVersion: CollaborationNotificationSettingsRevision?,
    val readVersion: CollaborationReadStateRevision?,
    val preview: CollaborationNotificationPreview,
    val senderName: String?,
    val body: String?,
) {
    override fun toString() = "CollaborationMessageNotification([redacted])"
}
