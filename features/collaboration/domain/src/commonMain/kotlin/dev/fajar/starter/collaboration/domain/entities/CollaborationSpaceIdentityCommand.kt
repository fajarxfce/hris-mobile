package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

/** Immutable request binding; local login handles are deliberately absent. */
data class CollaborationSpaceIdentityBinding(
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
) {
    override fun toString() = "CollaborationSpaceIdentityBinding([redacted])"
}

fun CollaborationSpaceOwner.identityBinding() =
    CollaborationSpaceIdentityBinding(
        parent.audience,
        parent.accountId,
        spaceId,
        parent.nativeSessionId,
    )

/** A retained provider-user selection request; not a provider identity or access grant. */
data class CollaborationSpaceIdentityCommand(
    val id: String,
    val binding: CollaborationSpaceIdentityBinding,
    val platform: CollaborationNativePlatform,
    val clientBuild: Long,
    val createdAt: Instant,
) {
    override fun toString() = "CollaborationSpaceIdentityCommand([redacted])"
}

sealed interface CollaborationSpaceIdentityOutcome {
    data object Pending : CollaborationSpaceIdentityOutcome

    /** Stored before transport; a missing response never proves the operation was rejected. */
    data class Unconfirmed(val failure: Failure? = null) : CollaborationSpaceIdentityOutcome

    /** The server receipt names the account, not its Matrix user or any registered device. */
    data class Selected(val receipt: CollaborationSetupReceipt) : CollaborationSpaceIdentityOutcome
}

data class CollaborationSpaceIdentityCommandRecord(
    val command: CollaborationSpaceIdentityCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: CollaborationSpaceIdentityOutcome,
) {
    override fun toString() =
        "CollaborationSpaceIdentityCommandRecord(revision=$revision, attempts=$attempts)"
}
