package dev.fajar.starter.collaboration.domain.entities

/** Observed receipts only. An absent native transaction is not proof that an intent was sent. */
data class CollaborationQueueReceipt(val transactionId: String, val eventId: String) {
    override fun toString() = "CollaborationQueueReceipt([redacted])"
}

enum class CollaborationQueueDeliveryReason {
    DRAINED,
    TIMED_OUT,
    STOPPED,
    FAILED,
    UNRESOLVED,
}

data class CollaborationQueueDelivery(
    val receipts: List<CollaborationQueueReceipt>,
    val pending: Set<String>,
    val reason: CollaborationQueueDeliveryReason,
) {
    override fun toString() = "CollaborationQueueDelivery([redacted])"
}
