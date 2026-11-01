package dev.fajar.starter.collaboration.domain.entities

/** One bounded delivery attempt. IDs are logical application messages, never replacement sends. */
data class CollaborationDeliveryReport(
    val confirmed: Set<String>,
    val unresolved: Set<String>,
    val nativeReason: CollaborationQueueDeliveryReason?,
) {
    override fun toString() = "CollaborationDeliveryReport([redacted])"
}
