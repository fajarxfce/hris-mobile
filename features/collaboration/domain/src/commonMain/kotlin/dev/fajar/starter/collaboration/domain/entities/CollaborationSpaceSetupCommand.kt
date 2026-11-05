package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

/** Common immutable native operation fields; implementations have different idempotency inputs. */
sealed interface CollaborationSpaceSetupRequest {
    val operationId: String
    val binding: CollaborationSpaceDeviceBinding
    val deviceId: String
    val deviceVersion: Long
}

/** Owns original registration reference and request; local handles are deliberately absent. */
data class CollaborationSpaceSetupCommand(
    val registrationId: String,
    val platform: CollaborationNativePlatform,
    val clientBuild: Long,
    val createdAt: Instant,
    val request: CollaborationSpaceSetupRequest,
) {
    val id: String
        get() = request.operationId

    val binding: CollaborationSpaceDeviceBinding
        get() = request.binding

    override fun toString() = "CollaborationSpaceSetupCommand([redacted])"
}

sealed interface CollaborationSpaceSetupOutcome {
    data object Pending : CollaborationSpaceSetupOutcome

    /** Persist before transport. Rejection cannot disprove an earlier uncertain completion. */
    data class Unconfirmed(val failure: Failure? = null) : CollaborationSpaceSetupOutcome

    data class Retried(val receipt: CollaborationSetupReceipt) : CollaborationSpaceSetupOutcome

    data class Bootstrapped(val gateway: CollaborationSpaceGateway) :
        CollaborationSpaceSetupOutcome {
        override fun toString() = "Bootstrapped([redacted])"
    }
}

data class CollaborationSpaceSetupRecord(
    val command: CollaborationSpaceSetupCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: CollaborationSpaceSetupOutcome,
) {
    override fun toString() =
        "CollaborationSpaceSetupRecord(revision=$revision, attempts=$attempts)"
}
