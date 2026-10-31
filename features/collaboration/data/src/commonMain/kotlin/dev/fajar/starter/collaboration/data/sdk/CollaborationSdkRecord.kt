package dev.fajar.starter.collaboration.data.sdk

import kotlinx.serialization.Serializable

/** Serialized only inside the platform protected credential record. Never a diagnostic value. */
@Serializable
internal data class CollaborationSdkRecord(
    val schema: Int,
    val storeId: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val passphrase: String,
    val bootstrapOperationId: String,
    val bootstrapGatewayId: String,
    val bootstrapGatewayVersion: Long,
    val bootstrapClientBuild: Long,
    val initialAccessExpiresAt: String,
    val expiresAt: String,
    val accessToken: String,
    val refreshToken: String,
    val initialized: Boolean,
    val credentialRevision: Long,
) {
    override fun toString() = "CollaborationSdkRecord([redacted])"
}
