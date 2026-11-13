package dev.fajar.starter.collaboration.domain.entities

/**
 * One coherent owned source revision. Input order and immutable original identities are retained.
 */
data class CollaborationSpaceHistorySnapshot(
    val revision: String?,
    val events: List<CollaborationSpaceHistorySnapshotEvent>,
) {
    override fun toString() = "CollaborationSpaceHistorySnapshot([redacted])"
}

/** Null original means no materialized original, including a tombstone received before its text. */
data class CollaborationSpaceHistorySnapshotEvent(
    val eventId: String,
    val original: CollaborationHistoryMessage?,
) {
    override fun toString() = "CollaborationSpaceHistorySnapshotEvent([redacted])"
}
