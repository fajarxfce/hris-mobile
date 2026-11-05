package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

/** Immutable native request identity, independent of replaceable local login handles. */
data class CollaborationSpaceDeviceBinding(
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
) {
    override fun toString() = "CollaborationSpaceDeviceBinding([redacted])"
}

/** Original physical SDK provenance; its native family must never be silently replaced. */
data class CollaborationSpaceLegacySdk(
    val binding: CollaborationSetupBinding,
    val deviceId: String,
    val identity: CollaborationLocalDeviceIdentity,
) {
    override fun toString() = "CollaborationSpaceLegacySdk([redacted])"
}

/**
 * Retained intent only. Preparation must independently establish current admission/SDK ownership.
 */
data class CollaborationSpaceRegistrationCommand(
    val id: String,
    val binding: CollaborationSpaceDeviceBinding,
    val platform: CollaborationNativePlatform,
    val clientBuild: Long,
    val createdAt: Instant,
    val provider: CollaborationSpaceIdentity,
    val label: String,
    val origin: CollaborationSpaceDeviceOrigin,
    val legacy: CollaborationSpaceLegacySdk?,
) {
    override fun toString() = "CollaborationSpaceRegistrationCommand([redacted])"
}

sealed interface CollaborationSpaceRegistrationOutcome {
    data object Pending : CollaborationSpaceRegistrationOutcome

    /**
     * Persist before HTTP. Missing response or a later rejection cannot disprove earlier success.
     */
    data class Unconfirmed(val failure: Failure? = null) : CollaborationSpaceRegistrationOutcome

    data class Registered(val receipt: CollaborationSetupReceipt) :
        CollaborationSpaceRegistrationOutcome
}

data class CollaborationSpaceRegistrationRecord(
    val command: CollaborationSpaceRegistrationCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: CollaborationSpaceRegistrationOutcome,
) {
    override fun toString() =
        "CollaborationSpaceRegistrationRecord(revision=$revision, attempts=$attempts)"
}
