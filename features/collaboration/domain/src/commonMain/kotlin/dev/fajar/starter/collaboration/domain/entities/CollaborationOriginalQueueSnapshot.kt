package dev.fajar.starter.collaboration.domain.entities

/** Original physical provenance and complete paused queue facts; never a delivery grant. */
data class CollaborationOriginalQueueSnapshot(
    val binding: CollaborationSetupBinding,
    val device: CollaborationMessageDevice,
    val messages: List<CollaborationQueuedOriginal>,
) {
    override fun toString() = "CollaborationOriginalQueueSnapshot([redacted])"
}

/** Logical owner stays separate from the retained physical message namespace. */
data class CollaborationSpaceOriginalQueueSnapshot(
    val runtime: CollaborationSpaceRuntimeReady,
    val original: CollaborationOriginalQueueSnapshot,
) {
    override fun toString() = "CollaborationSpaceOriginalQueueSnapshot([redacted])"
}
