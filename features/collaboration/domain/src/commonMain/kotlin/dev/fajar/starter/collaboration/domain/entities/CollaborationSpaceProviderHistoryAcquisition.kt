package dev.fajar.starter.collaboration.domain.entities

/** Exact local evidence from an admitted provider acquisition; it grants no content completion. */
data class CollaborationSpaceProviderHistoryAcquisition(
    val runtime: CollaborationSpaceRuntimeReady,
    val traversal: CollaborationHistoryTraversal,
    val origin: CollaborationHistoryPageOrigin,
    val previous: CollaborationHistoryProgress?,
    val progress: CollaborationHistoryProgress,
    val references: Int,
) {
    val exhausted: Boolean
        get() = progress.exhausted

    override fun toString() = "CollaborationSpaceProviderHistoryAcquisition([redacted])"
}
