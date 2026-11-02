package dev.fajar.starter.collaboration.domain.entities

/** Account-owned durable seek; contains no company content or send authority. */
data class CollaborationNotificationReplyRecoveryCursor(
    val generation: String,
    val afterSequence: Long,
) {
    override fun toString() = "NotificationReplyRecoveryCursor([redacted])"
}

data class CollaborationNotificationReplyRecoveryReference(val id: String, val sequence: Long) {
    override fun toString() = "NotificationReplyRecoveryReference([redacted])"
}

/** Bounded opaque discovery, not a company content read or action grant. */
data class CollaborationNotificationReplyRecoveryWork(
    val parent: CollaborationNativeParent,
    val cursor: CollaborationNotificationReplyRecoveryCursor?,
    val afterSequence: Long?,
    val entries: List<CollaborationNotificationReplyRecoveryReference>,
    val hasMore: Boolean,
) {
    override fun toString() = "NotificationReplyRecoveryWork([redacted])"
}
