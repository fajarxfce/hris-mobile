package dev.fajar.starter.collaboration.domain.entities

/** A local callback snapshot, not a live write grant or a native display/cancellation lease. */
data class CollaborationNotificationInvocation(
    val scope: CollaborationLocalScope.Captured,
    val action: CollaborationNotificationAction,
) {
    override fun toString() = "CollaborationNotificationInvocation([redacted])"
}
