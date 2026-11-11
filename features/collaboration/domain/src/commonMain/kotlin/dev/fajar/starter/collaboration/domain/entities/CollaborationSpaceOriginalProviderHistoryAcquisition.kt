package dev.fajar.starter.collaboration.domain.entities

/** Evidence for one original Company cursor transition, not content or queue completion. */
data class CollaborationSpaceOriginalProviderHistoryAcquisition(
    val runtime: CollaborationSpaceRuntimeReady,
    val room: CollaborationSpaceOriginalIncomingRoom,
    val origin: CollaborationHistoryPageOrigin,
    val previous: CollaborationHistoryProgress?,
    val progress: CollaborationHistoryProgress,
    val references: Int,
) {
    override fun toString() = "CollaborationSpaceOriginalProviderHistoryAcquisition([redacted])"
}
