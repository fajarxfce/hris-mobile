package dev.fajar.starter.collaboration.domain.entities

/**
 * Partitions the captured reference IDs after source-checked publication. Unresolved references
 * retain durable incoming/backfill ownership; this result neither acknowledges nor discards them.
 */
data class CollaborationSpaceInboxHistoryCommit(
    val owner: CollaborationHistoryChangeOwner,
    val receipt: CollaborationHistoryInboxReceipt,
    val retainedEventIds: List<String>,
    val unresolvedEventIds: List<String>,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryCommit([redacted])"
}
