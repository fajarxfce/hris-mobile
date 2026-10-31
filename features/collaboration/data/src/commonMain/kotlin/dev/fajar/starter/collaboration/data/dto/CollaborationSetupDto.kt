package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable data class CollaborationSetupDto(val device: CollaborationDeviceDto?)

@Serializable
data class CollaborationDeviceDto(
    val id: String,
    val version: Long,
    val state: String,
    val platform: String,
    val provisioning: CollaborationProvisioningDto?,
    val gateway: CollaborationGatewayStatusDto?,
)

@Serializable
data class CollaborationProvisioningDto(
    val version: Long,
    val jobId: String,
    val status: String,
    val attempts: Int,
    val failureCode: String?,
    val retryNotBefore: String?,
)

@Serializable
data class CollaborationGatewayStatusDto(
    val id: String,
    val version: Long,
    val clientBuild: Long,
    val accessExpiresAt: String,
    val expiresAt: String,
    val revokedAt: String?,
)

@Serializable data class CollaborationReceiptDto(val id: String, val version: Long)

@Serializable
class CollaborationGatewayDto(
    val sessionId: String,
    val version: Long,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: String,
    val expiresAt: String,
) {
    override fun toString() = "CollaborationGatewayDto([redacted])"
}

@Serializable
data class RegisterCollaborationDeviceRequest(val platform: String, val label: String) {
    override fun toString() = "RegisterCollaborationDeviceRequest([redacted])"
}

@Serializable
data class RetryCollaborationDeviceRequest(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedProvisioningVersion: Long,
)

@Serializable
data class BootstrapCollaborationDeviceRequest(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedGatewayVersion: Long?,
)
