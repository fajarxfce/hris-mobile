package dev.fajar.starter.collaboration.domain.entities

/** Local work identity, not live authority. No message body or provider credential escapes. */
data class CollaborationDeliveryWork(
    val scope: CollaborationLocalScope,
    val access: CollaborationLocalAccess,
    val conversationIds: List<String>,
    val cursor: CollaborationDeliveryCursor?,
) {
    constructor(
        workspaceId: String,
        access: CollaborationLocalAccess,
        conversationIds: List<String>,
        cursor: CollaborationDeliveryCursor?,
    ) : this(CollaborationLocalScope.Selected(workspaceId), access, conversationIds, cursor)

    override fun toString() = "CollaborationDeliveryWork([redacted])"
}
