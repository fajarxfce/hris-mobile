package dev.fajar.starter.collaboration.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CollaborationSpaceRetryRequestDto(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedProvisioningRevision: Long,
) {
    override fun toString() = "CollaborationSpaceRetryRequestDto([redacted])"
}

@Serializable
data class CollaborationSpaceBootstrapRequestDto(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedGatewayVersion: Long?,
) {
    override fun toString() = "CollaborationSpaceBootstrapRequestDto([redacted])"
}

@Serializable
data class CollaborationSpaceGatewayDto(
    val spaceId: String,
    val accountId: String,
    val deviceId: String,
    val nativeSessionId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val sessionId: String,
    val version: Long,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: String,
    val expiresAt: String,
) {
    override fun toString() = "CollaborationSpaceGatewayDto([redacted])"
}
