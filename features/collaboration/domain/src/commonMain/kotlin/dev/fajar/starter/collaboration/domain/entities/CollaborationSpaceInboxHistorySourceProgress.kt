package dev.fajar.starter.collaboration.domain.entities

/** Physical progress version, independent of source history and destination index revisions. */
data class CollaborationSpaceInboxHistoryProgressVersion(
    val generation: String,
    val revision: Long,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryProgressVersion([redacted])"
}

data class CollaborationSpaceInboxHistorySourceCheckpoint(
    val version: CollaborationSpaceInboxHistoryProgressVersion,
    val progress: CollaborationSpaceInboxHistorySourceProgress,
) {
    override fun toString() = "CollaborationSpaceInboxHistorySourceCheckpoint([redacted])"
}

/**
 * One canonical conversation's actual source. Both finite lanes yield to other conversations;
 * unresolved references can remain retained after this pass finishes. A replaced destination
 * generation requires reconstruction even when the raw history signal has not changed.
 */
data class CollaborationSpaceInboxHistorySourceProgress(
    val conversationId: String,
    val owner: CollaborationHistoryChangeOwner,
    val destinationGeneration: String?,
    val passId: String,
    val historyCursor: CollaborationHistoryChangeCursor?,
    val historyComplete: Boolean,
    val pendingWindow: CollaborationSpaceInboxHistoryPendingWindow?,
) {
    override fun toString() = "CollaborationSpaceInboxHistorySourceProgress([redacted])"
}

/**
 * A frozen interval of retained reference insertions, including an explicitly finished empty one.
 */
data class CollaborationSpaceInboxHistoryPendingWindow(
    val generation: String,
    val throughRevision: Long,
    val after: String?,
    val complete: Boolean,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryPendingWindow([redacted])"
}

/** First retention identity is preserved until an exact completion; replay cannot renew demand. */
data class CollaborationSpaceInboxHistoryRepairReference(
    val conversationId: String,
    val owner: CollaborationHistoryChangeOwner,
    val eventId: String,
    val insertedAtRevision: Long,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryRepairReference([redacted])"
}
