package dev.fajar.starter.collaboration.domain.entities

/** A finite account-owned sweep of opaque device references. It contains no text or send grant. */
data class CollaborationNotificationInputRecoveryState(
    val cycleId: String,
    val references: List<CollaborationNotificationInputReference>,
    val nextIndex: Int = 0,
    val inFlight: Boolean = false,
    val retryRequired: Boolean = false,
    val continuationRequired: Boolean = false,
    val finished: Boolean = false,
) {
    override fun toString() = "NotificationInputRecoveryState([redacted])"
}

data class CollaborationNotificationInputRecoveryCursor(
    val generation: String,
    val state: CollaborationNotificationInputRecoveryState,
) {
    override fun toString() = "NotificationInputRecoveryCursor([redacted])"
}

data class CollaborationNotificationInputRecoverySnapshot(
    val parent: CollaborationNativeParent,
    val cursor: CollaborationNotificationInputRecoveryCursor,
) {
    override fun toString() = "NotificationInputRecoverySnapshot([redacted])"
}

sealed interface CollaborationNotificationInputRecoveryChange {
    data class Attempt(val reference: CollaborationNotificationInputReference) :
        CollaborationNotificationInputRecoveryChange {
        override fun toString() = "NotificationInputRecoveryAttempt([redacted])"
    }

    data class Settle(val retryRequired: Boolean) : CollaborationNotificationInputRecoveryChange

    data object Finish : CollaborationNotificationInputRecoveryChange
}
