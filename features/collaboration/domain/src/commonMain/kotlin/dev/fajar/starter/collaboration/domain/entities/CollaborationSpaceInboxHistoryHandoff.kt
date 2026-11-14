package dev.fajar.starter.collaboration.domain.entities

/**
 * A proposed atomic cursor/reference handoff after checked publication. This is not a storage
 * receipt: the owner must retain it against the exact progress checkpoint and current index.
 */
data class CollaborationSpaceInboxHistoryHandoff(
    val expected: CollaborationSpaceInboxHistorySourceCheckpoint,
    val replacement: CollaborationSpaceInboxHistorySourceProgress,
    val receipt: CollaborationHistoryInboxReceipt,
    val retainedEventIds: List<String>,
    val unresolvedEventIds: List<String>,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryHandoff([redacted])"
}
