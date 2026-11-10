package dev.fajar.starter.collaboration.domain.entities

/** Observed acknowledgements for one exact organization intent and original physical mapping. */
data class CollaborationSpaceMessageReceipt(
    val intent: CollaborationSpaceMessageIntent,
    val device: CollaborationMessageDevice,
    val transactionId: String,
    val origin: CollaborationMessageOrigin,
    val eventIds: List<String>,
) {
    override fun toString() = "CollaborationSpaceMessageReceipt([redacted])"
}
