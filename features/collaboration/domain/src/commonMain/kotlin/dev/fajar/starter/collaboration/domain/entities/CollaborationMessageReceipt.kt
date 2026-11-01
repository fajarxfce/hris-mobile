package dev.fajar.starter.collaboration.domain.entities

/** Observed server acknowledgements for one immutable outbound message, in observation order. */
data class CollaborationMessageReceipt(
    val intent: CollaborationMessageIntent,
    val device: CollaborationMessageDevice,
    val transactionId: String,
    val eventIds: List<String>,
) {
    override fun toString() = "CollaborationMessageReceipt([redacted])"
}
