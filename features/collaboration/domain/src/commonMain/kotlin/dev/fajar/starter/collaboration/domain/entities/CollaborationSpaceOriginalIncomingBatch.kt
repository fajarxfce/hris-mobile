package dev.fajar.starter.collaboration.domain.entities

/** Acknowledged Original retention attempts; neither live permission nor traversal completion. */
data class CollaborationSpaceOriginalIncomingBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val room: CollaborationSpaceOriginalIncomingRoom,
    val items: List<CollaborationIncomingHistoryResult>,
) {
    override fun toString() = "CollaborationSpaceOriginalIncomingBatch([redacted])"
}
