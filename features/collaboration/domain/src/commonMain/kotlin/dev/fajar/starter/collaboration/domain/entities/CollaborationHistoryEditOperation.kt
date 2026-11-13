package dev.fajar.starter.collaboration.domain.entities

/** Exact sender/operation lookup within the caller's canonical physical history partition. */
data class CollaborationHistoryEditOperation(val sender: String, val id: String) {
    override fun toString() = "CollaborationHistoryEditOperation([redacted])"
}

/** All retained aliases of one requested replacement, including an explicitly empty lookup. */
data class CollaborationHistoryEditOperationEvidence(
    val operation: CollaborationHistoryEditOperation,
    val events: List<CollaborationEditEvidence>,
) {
    override fun toString() = "CollaborationHistoryEditOperationEvidence([redacted])"
}
