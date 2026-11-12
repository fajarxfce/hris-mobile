package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceOwner

/** Logical live ownership; original physical owner/device/store remain on the reservation. */
internal data class SdkSpaceRuntimeIdentity(
    val owner: CollaborationSpaceOwner,
    val deviceId: String,
    val operationId: String,
    val gatewaySessionId: String,
    val gatewayVersion: Long,
) {
    /** Exact replay or one newer bootstrap for the same gateway; never downgrade a live slot. */
    fun canBeReplacedBy(next: SdkSpaceRuntimeIdentity): Boolean =
        this == next ||
            (owner == next.owner &&
                deviceId == next.deviceId &&
                gatewaySessionId == next.gatewaySessionId &&
                operationId != next.operationId &&
                gatewayVersion < next.gatewayVersion)

    override fun toString() = "SdkSpaceRuntimeIdentity([redacted])"
}
