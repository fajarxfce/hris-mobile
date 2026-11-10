package dev.fajar.starter.collaboration.domain.entities

/** Logical ownership alongside the original client's unmodified physical provider position. */
data class CollaborationSpaceProviderHistoryPage(
    val runtime: CollaborationSpaceRuntimeReady,
    val physical: CollaborationProviderHistoryPage,
) {
    override fun toString() = "CollaborationSpaceProviderHistoryPage([redacted])"
}
