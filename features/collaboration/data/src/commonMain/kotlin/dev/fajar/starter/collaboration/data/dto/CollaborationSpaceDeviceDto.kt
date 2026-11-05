package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceDeviceResponseDto(val device: CollaborationSpaceDeviceDto?)

@Serializable
data class CollaborationSpaceDeviceDto(
    val id: String,
    val version: Long,
    val state: String,
    val platform: String,
    val origin: String,
    val label: String,
    val provisioning: CollaborationSpaceProvisioningDto,
    val observedAt: String,
    val gateway: CollaborationGatewayStatusDto?,
) {
    override fun toString() = "CollaborationSpaceDeviceDto([redacted])"
}

@Serializable
data class CollaborationSpaceProvisioningDto(
    val revision: Long,
    val state: String,
    val attempts: Int,
    val expiresAt: String,
    val lastOutcome: CollaborationSpaceProvisioningOutcomeDto?,
    val retryNotBefore: String?,
)

@Serializable
data class CollaborationSpaceProvisioningOutcomeDto(
    val revision: Long,
    val state: String,
    val at: String,
    val failure: String?,
)
