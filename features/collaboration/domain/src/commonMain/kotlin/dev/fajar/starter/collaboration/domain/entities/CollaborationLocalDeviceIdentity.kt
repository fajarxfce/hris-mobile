package dev.fajar.starter.collaboration.domain.entities

/** Local SDK identity observation; neither server admission nor a completed ownership transfer. */
data class CollaborationLocalDeviceIdentity(
    val matrixUserId: String,
    val matrixDeviceId: String,
    val fingerprint: String,
) {
    override fun toString() = "CollaborationLocalDeviceIdentity([redacted])"
}
