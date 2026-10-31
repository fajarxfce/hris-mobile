package dev.fajar.starter.collaboration.domain.entities

data class CollaborationSetup(val owner: CollaborationOwner, val device: CollaborationDeviceSetup?)

/** Observed setup metadata; age alone never deletes a retained device or its encrypted store. */
data class CollaborationDeviceSetup(
    val id: String,
    val version: Long,
    val state: CollaborationDeviceState,
    val platform: CollaborationNativePlatform,
    val provisioning: CollaborationProvisioning?,
    val gateway: CollaborationGatewayStatus?,
) {
    override fun toString() = "CollaborationDeviceSetup(state=$state, version=$version)"
}

enum class CollaborationDeviceState {
    PROVISIONING,
    ACTIVE,
    REVOKED,
}

enum class CollaborationProvisioningState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class CollaborationProvisioning(
    val version: Long,
    val jobId: String,
    val state: CollaborationProvisioningState,
    val attempts: Int,
    val failureCode: String?,
    val retryNotBeforeEpochMillis: Long?,
)

data class CollaborationGatewayStatus(
    val id: String,
    val version: Long,
    val clientBuild: Long,
    val accessExpiresAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val revokedAtEpochMillis: Long?,
)
