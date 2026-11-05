package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Metadata for one native parent. Absence never authorizes replacement of an old SDK store. */
data class CollaborationSpaceDeviceObservation(
    val owner: CollaborationSpaceOwner,
    val device: CollaborationSpaceDeviceStatus?,
) {
    override fun toString() = "CollaborationSpaceDeviceObservation([redacted])"
}

/** No provider identity, private-key proof, token or permission to resume a runtime. */
data class CollaborationSpaceDeviceStatus(
    val id: String,
    val version: Long,
    val state: CollaborationDeviceState,
    val platform: CollaborationNativePlatform,
    val origin: CollaborationSpaceDeviceOrigin,
    val label: String,
    val provisioning: CollaborationSpaceProvisioningStatus,
    val observedAt: Instant,
    val gateway: CollaborationSpaceGatewayStatus?,
) {
    override fun toString() = "CollaborationSpaceDeviceStatus(state=$state, version=$version)"
}

enum class CollaborationSpaceDeviceOrigin {
    LEGACY,
    SPACE,
}

data class CollaborationSpaceProvisioningStatus(
    val revision: Long,
    val state: CollaborationProvisioningState,
    val attempts: Int,
    val expiresAt: Instant,
    val lastOutcome: CollaborationSpaceProvisioningOutcome?,
    val retryNotBefore: Instant?,
)

data class CollaborationSpaceProvisioningOutcome(
    val revision: Long,
    val state: CollaborationProvisioningState,
    val at: Instant,
    val failure: CollaborationSpaceProvisioningFailure?,
)

enum class CollaborationSpaceProvisioningFailure {
    REQUEST_EXPIRED,
    ATTEMPTS_EXHAUSTED,
    ACCESS_DENIED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_IDENTITY_CHANGED,
    PUBLICATION_CHANGED,
}

data class CollaborationSpaceGatewayStatus(
    val id: String,
    val version: Long,
    val clientBuild: Long,
    val accessExpiresAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
) {
    override fun toString() = "CollaborationSpaceGatewayStatus(version=$version)"
}
