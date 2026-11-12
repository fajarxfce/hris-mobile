package dev.fajar.starter.collaboration.domain.entities

/** Outcomes bound to the exact finite reference page checked during content acquisition. */
data class CollaborationSpaceOriginalKeyPageBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val history: CollaborationSpaceOriginalKeyHistory,
    val references: CollaborationHistoryReferences,
    val items: List<CollaborationIncomingHistoryResult>,
) {
    override fun toString() = "CollaborationSpaceOriginalKeyPageBatch([redacted])"
}
