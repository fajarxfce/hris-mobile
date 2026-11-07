package dev.fajar.starter.collaboration.data.sdk

import kotlinx.serialization.Serializable

/** Group credentials inside the original protected store; the original envelope stays intact. */
@Serializable
internal data class CollaborationSdkAdoption(
    val sourceSchema: Int,
    val spaceId: String,
    val deviceId: String,
    val deviceVersion: Long,
    val nativeSessionId: String,
    val registrationId: String,
    val bootstrapOperationId: String,
    val gatewayId: String,
    val gatewayVersion: Long,
    val clientBuild: Long,
    val initialAccessExpiresAt: String,
    val expiresAt: String,
    val initialAccessToken: String,
    val initialRefreshToken: String,
    val accessToken: String,
    val refreshToken: String,
    val initialized: Boolean,
) {
    override fun toString() = "CollaborationSdkAdoption([redacted])"
}
