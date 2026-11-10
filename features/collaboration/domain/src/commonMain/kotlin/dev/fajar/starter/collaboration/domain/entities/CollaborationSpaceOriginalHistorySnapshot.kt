package dev.fajar.starter.collaboration.domain.entities

/** A bounded original window. Logical runtime and retained physical identity remain distinct. */
data class CollaborationSpaceOriginalHistorySnapshot(
    val runtime: CollaborationSpaceRuntimeReady,
    val head: CollaborationHistoryHead,
    val window: CollaborationOriginalWindow?,
) {
    override fun toString() = "CollaborationSpaceOriginalHistorySnapshot([redacted])"
}
