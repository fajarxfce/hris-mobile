package dev.fajar.starter.collaboration.domain.entities

/** Exact canonical source, including absence; acquisition alone never completes index repair. */
data class CollaborationSpaceHistoryChangesRead(
    val owner: CollaborationHistoryChangeOwner,
    val changes: CollaborationHistoryChanges,
) {
    override fun toString() = "CollaborationSpaceHistoryChangesRead([redacted])"
}
