package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind

/** Both immutable attempts remain owned facts; preparing a replacement does not resolve either. */
data class CollaborationSpaceBootstrapReplacement(
    val supersedes: CollaborationSpaceBootstrap,
    val replacement: CollaborationSpaceBootstrap,
    val retainedRestriction: CollaborationSpaceSetupRestriction? = null,
) : CollaborationSpaceSetupRequest {
    override val operationId
        get() = replacement.operationId

    override val binding
        get() = replacement.binding

    override val deviceId
        get() = replacement.deviceId

    override val deviceVersion
        get() = replacement.deviceVersion

    override fun toString() = "CollaborationSpaceBootstrapReplacement([redacted])"
}

/** Typed supersession proof, not an acknowledgment that the original historically applied. */
data class CollaborationSpaceBootstrapReplacementReceipt(
    val superseded: CollaborationSpaceBootstrap,
    val replacementOperationId: String,
    val gateway: CollaborationSpaceGateway,
) {
    override fun toString() = "CollaborationSpaceBootstrapReplacementReceipt([redacted])"
}

/** Bounded local denial provenance. It is never part of the server operation payload. */
data class CollaborationSpaceSetupRestriction(
    val kind: FailureKind,
    val code: String?,
    val retryAfterSeconds: Long?,
) {
    fun failure() =
        Failure(
            kind,
            "Native setup could not be confirmed.",
            code = code,
            retryAfterSeconds = retryAfterSeconds,
        )
}
