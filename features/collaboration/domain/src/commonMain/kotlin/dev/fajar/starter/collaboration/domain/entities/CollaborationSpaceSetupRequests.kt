package dev.fajar.starter.collaboration.domain.entities

/**
 * Original native request identity. Retry receipts carry provisioning revision, not device version.
 */
data class CollaborationSpaceProvisioningRetry(
    val operationId: String,
    val binding: CollaborationSpaceDeviceBinding,
    val deviceId: String,
    val deviceVersion: Long,
    val provisioningRevision: Long,
) {
    override fun toString() = "CollaborationSpaceProvisioningRetry([redacted])"
}

/** Bootstrap idempotency binds the actual client build as well as the original native parent. */
data class CollaborationSpaceBootstrap(
    val operationId: String,
    val binding: CollaborationSpaceDeviceBinding,
    val platform: CollaborationNativePlatform,
    val clientBuild: Long,
    val deviceId: String,
    val deviceVersion: Long,
    val gatewayVersion: Long?,
    val provider: CollaborationSpaceIdentity,
    val matrixDeviceId: String,
) {
    override fun toString() = "CollaborationSpaceBootstrap([redacted])"
}

/** Sensitive result for the owning durable setup/SDK store; never a UI model or admission grant. */
data class CollaborationSpaceGateway(
    val binding: CollaborationSpaceDeviceBinding,
    val deviceId: String,
    val sessionId: String,
    val version: Long,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "CollaborationSpaceGateway([redacted])"
}
