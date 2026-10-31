package dev.fajar.starter.collaboration.domain.entities

/** The application retains each operation before I/O; adapters never generate retry identities. */
data class CollaborationRegistration(
    val operationId: String,
    val platform: CollaborationNativePlatform,
    val label: String,
) {
    override fun toString() = "CollaborationRegistration([redacted])"
}

data class CollaborationProvisioningRetry(
    val operationId: String,
    val deviceId: String,
    val deviceVersion: Long,
    val provisioningVersion: Long,
)

data class CollaborationBootstrap(
    val operationId: String,
    val deviceId: String,
    val deviceVersion: Long,
    val gatewayVersion: Long?,
)

data class CollaborationSetupReceipt(val id: String, val version: Long)
