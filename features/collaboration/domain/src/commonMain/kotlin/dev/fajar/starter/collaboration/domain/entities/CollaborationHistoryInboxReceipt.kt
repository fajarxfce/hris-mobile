package dev.fajar.starter.collaboration.domain.entities

/** Physical local source revision; independent from the opaque snapshot comparison token. */
data class CollaborationHistorySourceVersion(val generation: String, val revision: Long) {
    override fun toString() = "CollaborationHistorySourceVersion([redacted])"
}

/**
 * A null projection confirms an unchanged source/index observation for unresolved-only work. It
 * cannot create either partition. A projection must target this exact canonical conversation and
 * expected index; repositories independently authenticate both retained checkpoints.
 */
data class CollaborationHistoryInboxProposal(
    val conversationId: String,
    val expected: CollaborationSpaceInboxVersion?,
    val projection: CollaborationSpaceInboxPublication?,
) {
    override fun toString() = "CollaborationHistoryInboxProposal([redacted])"
}

/**
 * Actual source and destination versions from a checked no-op or successful conditional write. Null
 * source/destination means confirmed absence, never an uncertain write. This receipt alone does not
 * complete traversal: the owner must atomically retain cursor and unresolved references.
 */
data class CollaborationHistoryInboxReceipt(
    val source: CollaborationHistorySourceVersion?,
    val destination: CollaborationSpaceInboxVersion?,
) {
    override fun toString() = "CollaborationHistoryInboxReceipt([redacted])"
}
