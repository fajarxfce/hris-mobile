package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceDeviceRepository {
    /** Only an owning use case may send an already retained exact registration command. */
    suspend fun register(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceRegistrationCommand,
    ): AppResult<CollaborationSetupReceipt>

    /**
     * One metadata read, with no enrollment, provisioning retry or provider credential acquisition.
     */
    suspend fun status(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceDeviceObservation>
}
