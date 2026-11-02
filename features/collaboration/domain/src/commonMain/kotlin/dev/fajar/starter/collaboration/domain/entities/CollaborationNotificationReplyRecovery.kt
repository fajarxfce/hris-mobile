package dev.fajar.starter.collaboration.domain.entities

/** Account-owned durable seek; contains no company content or send authority. */
data class CollaborationNotificationReplyRecoveryCursor(
    val generation: String,
    val afterSequence: Long,
    val cycle: CollaborationNotificationReplyRecoveryCycle? = null,
) {
    override fun toString() = "NotificationReplyRecoveryCursor([redacted])"
}

/** One durable sweep; its ID prevents old work from settling or advancing a replacement cycle. */
data class CollaborationNotificationReplyRecoveryCycle(
    val id: String,
    val initial: Boolean,
    val inFlight: Boolean,
    val retryRequired: Boolean,
    val finished: Boolean,
) {
    override fun toString() = "NotificationReplyRecoveryCycle([redacted])"
}

data class CollaborationNotificationReplyRecoverySnapshot(
    val parent: CollaborationNativeParent,
    val cursor: CollaborationNotificationReplyRecoveryCursor,
) {
    override fun toString() = "NotificationReplyRecoverySnapshot([redacted])"
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
