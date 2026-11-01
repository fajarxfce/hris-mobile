package dev.fajar.starter.collaboration.domain.entities

/** Local work identity, not live authority. No message body or provider credential escapes. */
data class CollaborationDeliveryWork(
    val workspaceId: String,
    val access: CollaborationLocalAccess,
    val conversationIds: List<String>,
    val cursor: CollaborationDeliveryCursor?,
) {
    override fun toString() = "CollaborationDeliveryWork([redacted])"
}
