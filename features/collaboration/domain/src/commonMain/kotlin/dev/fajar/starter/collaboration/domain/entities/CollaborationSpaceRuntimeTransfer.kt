package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Original local target/provenance; possession and live admission remain separate. */
data class CollaborationSpaceRuntimeTransfer(
    val binding: CollaborationSpaceDeviceBinding,
    val legacy: CollaborationSpaceLegacySdk,
    val intent: CollaborationRuntimeHandoverIntent,
) {
    override fun toString() = "CollaborationSpaceRuntimeTransfer([redacted])"
}

/** Server request-slot observation. Zero does not establish SDK shutdown or adoption. */
data class CollaborationSpaceRuntimeDrain(
    val spaceId: String,
    val deviceId: String,
    val runtimeVersion: Long,
    val activeRequests: Int,
    val latestRequestExpiresAt: Instant?,
    val observedAt: Instant,
) {
    override fun toString() = "CollaborationSpaceRuntimeDrain([redacted])"
}
