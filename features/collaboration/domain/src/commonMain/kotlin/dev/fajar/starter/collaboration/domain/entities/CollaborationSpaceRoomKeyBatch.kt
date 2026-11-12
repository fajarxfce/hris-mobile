package dev.fajar.starter.collaboration.domain.entities

/**
 * Copied room-key hints from one exact installed runtime; key material never crosses this boundary.
 */
data class CollaborationSpaceRoomKeyBatch(
    val runtime: CollaborationSpaceRuntimeReady,
    val source: CollaborationSpaceIncomingSource,
    val receiptId: String,
    val roomIds: List<String>,
    val reconcileRequired: Boolean,
) {
    override fun toString() = "CollaborationSpaceRoomKeyBatch([redacted])"
}
