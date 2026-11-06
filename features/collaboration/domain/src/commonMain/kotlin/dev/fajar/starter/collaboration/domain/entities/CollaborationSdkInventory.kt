package dev.fajar.starter.collaboration.domain.entities

/**
 * Owned local file/record facts. Presence does not establish private-key possession or admission.
 */
data class CollaborationSdkInventory(
    val binding: CollaborationSetupBinding,
    val deviceId: String,
    val directoryPresent: Boolean,
    val stateDatabasePresent: Boolean,
    val cryptoDatabasePresent: Boolean,
    val credential: CollaborationSdkCredentialInventory?,
) {
    override fun toString() = "CollaborationSdkInventory([redacted])"
}

/** Public provenance only; no private keys, passphrases, credentials, paths or live grant. */
data class CollaborationSdkCredentialInventory(
    val matrixUserId: String,
    val matrixDeviceId: String,
    val bootstrapOperationId: String,
    val messageOperationId: String,
    val revision: Long,
    val initialized: Boolean,
    val handover: CollaborationRuntimeHandoverIntent?,
) {
    override fun toString() = "CollaborationSdkCredentialInventory([redacted])"
}
