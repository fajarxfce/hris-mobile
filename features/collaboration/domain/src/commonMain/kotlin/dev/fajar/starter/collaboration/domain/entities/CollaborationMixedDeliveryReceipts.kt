package dev.fajar.starter.collaboration.domain.entities

/** Both batches are validated before either journal is written; each retains its actual owner. */
data class CollaborationMixedDeliveryReceipts(
    val company: List<CollaborationMessageReceipt>,
    val space: List<CollaborationSpaceMessageReceipt>,
) {
    override fun toString() = "CollaborationMixedDeliveryReceipts([redacted])"
}
