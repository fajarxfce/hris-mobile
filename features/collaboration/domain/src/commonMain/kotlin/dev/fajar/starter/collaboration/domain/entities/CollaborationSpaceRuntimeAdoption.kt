package dev.fajar.starter.collaboration.domain.entities

/** Exact durable inputs for one original-store acquisition, never a live permission grant. */
data class CollaborationSpaceRuntimeAdoption(
    val registration: CollaborationSpaceRegistrationRecord,
    val setup: CollaborationSpaceSetupRecord,
    val original: CollaborationExistingRuntimeHandover,
) {
    val gateway: CollaborationSpaceGateway?
        get() =
            when (val outcome = setup.outcome) {
                is CollaborationSpaceSetupOutcome.Bootstrapped -> outcome.gateway
                is CollaborationSpaceSetupOutcome.BootstrapReplaced -> outcome.receipt.gateway
                else -> null
            }

    override fun toString() = "CollaborationSpaceRuntimeAdoption([redacted])"
}

/** Application-owned readiness; presentation receives no raw SDK or parent close handle. */
data class CollaborationSpaceRuntimeReady(
    val owner: CollaborationSpaceOwner,
    val deviceId: String,
    val operationId: String,
) {
    override fun toString() = "CollaborationSpaceRuntimeReady([redacted])"
}
