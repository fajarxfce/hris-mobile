package dev.fajar.starter.collaboration.domain.entities

/**
 * One original from a coherent history snapshot and its admitted storage identity. resolvedEdit
 * names the exact pending pointer whose provider outcome or supersession the source owner proved.
 * Null retains the optimistic edit. It never means that a missing command acknowledged delivery.
 */
data class CollaborationSpaceInboxHistoryUpdate(
    val original: CollaborationHistoryMessage,
    val identity: CollaborationSpaceInboxHistorySource,
    val resolvedEdit: CollaborationSpaceInboxPendingEdit? = null,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryUpdate([redacted])"
}

/**
 * Unresolved originals still belong to durable incoming/backfill work after any partial publish.
 */
data class CollaborationSpaceInboxHistoryProjection(
    val publication: CollaborationSpaceInboxPublication?,
    val unresolvedEventIds: List<String>,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryProjection([redacted])"
}
