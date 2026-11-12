package dev.fajar.starter.collaboration.domain.entities

/**
 * Space history retains its own coordinates, including when its physical room is an Original alias.
 */
data class CollaborationSpaceKeyHistory(
    val source: CollaborationSpaceIncomingSource,
    val binding: CollaborationSpaceRoomBinding,
    val traversal: CollaborationHistoryTraversal,
) {
    override fun toString() = "CollaborationSpaceKeyHistory([redacted])"
}

/** Local binding absence is unresolved work; it does not discharge pending history. */
sealed interface CollaborationSpaceKeyHistoryResolution {
    data class Ready(val history: CollaborationSpaceKeyHistory) :
        CollaborationSpaceKeyHistoryResolution

    data object MetadataUnavailable : CollaborationSpaceKeyHistoryResolution

    /** The validated current source cannot serve this historical owner; no metadata was read. */
    data object SourceUnavailable : CollaborationSpaceKeyHistoryResolution
}
