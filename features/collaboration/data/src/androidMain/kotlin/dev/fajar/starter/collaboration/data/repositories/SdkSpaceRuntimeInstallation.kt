package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*

/** Exact protected-record inputs selected before acquiring the original directory. */
internal data class SdkSpaceRuntimeInstallation(
    val original: CollaborationExistingRuntimeHandover,
    val command: CollaborationSpaceSetupCommand,
    val gateway: CollaborationSpaceGateway,
) {
    override fun toString() = "SdkSpaceRuntimeInstallation([redacted])"
}
