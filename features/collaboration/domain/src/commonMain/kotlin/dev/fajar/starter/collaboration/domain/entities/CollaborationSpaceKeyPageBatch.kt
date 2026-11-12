package dev.fajar.starter.collaboration.domain.entities

/** Outcomes belong to the exact logical Space reference page admitted during acquisition. */
data class CollaborationSpaceKeyPageBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val history: CollaborationSpaceKeyHistory,
    val references: CollaborationHistoryReferences,
    val items: List<CollaborationIncomingHistoryResult>,
) {
    override fun toString() = "CollaborationSpaceKeyPageBatch([redacted])"
}
