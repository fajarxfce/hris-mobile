package dev.fajar.starter.collaboration.domain.entities

/**
 * Scoped content retention outcomes; neither reference completion nor a global recovery receipt.
 */
data class CollaborationSpaceOriginalKeyHistoryBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val history: CollaborationSpaceOriginalKeyHistory,
    val items: List<CollaborationIncomingHistoryResult>,
) {
    override fun toString() = "CollaborationSpaceOriginalKeyHistoryBatch([redacted])"
}
