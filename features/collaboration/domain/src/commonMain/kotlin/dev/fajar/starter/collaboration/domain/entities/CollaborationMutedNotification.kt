package dev.fajar.starter.collaboration.domain.entities

/** Reviewed source metadata only; neither native removal nor durable suppression has occurred. */
@ConsistentCopyVisibility
data class CollaborationMutedNotificationPlan
internal constructor(
    val parent: CollaborationNativeParent,
    val access: CollaborationLocalAccess,
    val conversationId: String,
    val roomId: String,
    val eventId: String,
    val contentHash: String,
    val timestamp: Long,
    val settings: CollaborationNotificationSettingsState,
    val previous: CollaborationNotificationPublicationReference? = null,
) {
    override fun toString() = "CollaborationMutedNotificationPlan([redacted])"
}

sealed interface CollaborationMutedNotificationPreparation {
    data object PendingHistory : CollaborationMutedNotificationPreparation

    data object AlreadySuppressed : CollaborationMutedNotificationPreparation

    data class Ready(val plan: CollaborationMutedNotificationPlan) :
        CollaborationMutedNotificationPreparation {
        override fun toString() = "CollaborationMutedNotificationPreparation.Ready([redacted])"
    }
}
