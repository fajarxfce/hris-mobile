package dev.fajar.starter.collaboration.domain.entities

/** Exact durable witness owned by the one serial key collector; carries no key material. */
data class CollaborationSpaceRoomKeyReceipt(
    val source: CollaborationSpaceIncomingSource,
    val runtimeDeviceId: String,
    val runtimeOperationId: String,
    val receiptId: String,
) {
    override fun toString() = "CollaborationSpaceRoomKeyReceipt([redacted])"
}
