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
    val handover: CollaborationSdkHandover? = null,
    /** Stable queue namespace, independent of replacement access/refresh credentials. */
    val queueOperationId: String? = null,
    val adoption: CollaborationSdkAdoption? = null,
    /** Confirmed server rejection; independent of private keys and queue ownership. */
    val authenticationRejected: Boolean = false,
) {
    val messageOperationId: String
        get() = queueOperationId ?: bootstrapOperationId

    override fun toString() = "CollaborationSdkRecord([redacted])"
}

/** Stored inside the same protected record as its original immutable SDK namespace. */
@Serializable
internal data class CollaborationSdkHandover(
    val operationId: String,
    val spaceId: String,
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val fingerprint: String,
) {
    override fun toString() = "CollaborationSdkHandover([redacted])"
}
