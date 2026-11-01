package dev.fajar.starter.collaboration.domain.entities

/**
 * A finite SDK snapshot around a known event; it carries no provider continuation or end marker.
 */
data class CollaborationOriginalWindow(
    val owner: CollaborationOwner,
    val deviceId: String,
    val roomId: String,
    val anchorEventId: String,
    val changes: List<CollaborationHistoryChange>,
    val unresolvedEventIds: List<String>,
) {
    override fun toString() = "CollaborationOriginalWindow([redacted])"
}

/** Counts acquired event facts only; unresolved entries may include unsupported state events. */
data class CollaborationHistoryAcquisition(val acquiredEvents: Int, val unresolvedEvents: Int)
