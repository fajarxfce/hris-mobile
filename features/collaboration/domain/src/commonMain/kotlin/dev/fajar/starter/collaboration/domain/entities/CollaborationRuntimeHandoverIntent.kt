package dev.fajar.starter.collaboration.domain.entities

/** Durable local intent; it grants no server authority and does not prove provider completion. */
data class CollaborationRuntimeHandoverIntent(
    val operationId: String,
    val spaceId: String,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val fingerprint: String,
) {
    override fun toString() = "CollaborationRuntimeHandoverIntent([redacted])"
}
