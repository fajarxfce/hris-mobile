package dev.fajar.starter.collaboration.domain.entities

/** One exact event through an existing physical client; no content admission or progress grant. */
data class CollaborationEventSnapshot(
    val owner: CollaborationOwner,
    val deviceId: String,
    val roomId: String,
    val roomJoined: Boolean,
    val roomEncrypted: Boolean,
    val event: CollaborationAcquiredEvent,
) {
    override fun toString() = "CollaborationEventSnapshot([redacted])"
}

/** Logical Space ownership never relabels the original client's physical identity. */
data class CollaborationSpaceEventSnapshot(
    val runtime: CollaborationSpaceRuntimeReady,
    val physical: CollaborationEventSnapshot,
) {
    override fun toString() = "CollaborationSpaceEventSnapshot([redacted])"
}

data class CollaborationSpaceEventHead(
    val runtime: CollaborationSpaceRuntimeReady,
    val head: CollaborationHistoryHead,
) {
    override fun toString() = "CollaborationSpaceEventHead([redacted])"
}
