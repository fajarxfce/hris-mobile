package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Immutable request identity, independent of the replaceable local session handle. */
data class CollaborationSetupBinding(
    val audience: String,
    val accountId: String,
    val companyId: String,
    val nativeSessionId: String,
) {
    override fun toString() = "CollaborationSetupBinding([redacted])"
}

fun CollaborationOwner.setupBinding() =
    CollaborationSetupBinding(audience, accountId, companyId, nativeSessionId)

sealed interface CollaborationSetupAction {
    data class Register(val label: String) : CollaborationSetupAction {
        override fun toString() = "Register([redacted])"
    }

    data class Retry(val deviceId: String, val deviceVersion: Long, val provisioningVersion: Long) :
        CollaborationSetupAction

    data class Bootstrap(val deviceId: String, val deviceVersion: Long, val gatewayVersion: Long?) :
        CollaborationSetupAction
}

/** Bootstrap idempotency includes platform/build, so an upgrade must not rewrite these values. */
data class CollaborationSetupCommand(
    val id: String,
    val binding: CollaborationSetupBinding,
    val platform: CollaborationNativePlatform,
    val clientBuild: Long,
    val createdAt: Instant,
    val action: CollaborationSetupAction,
) {
    override fun toString() = "CollaborationSetupCommand([redacted])"
}
