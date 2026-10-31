package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSetupRepository {
    suspend fun read(owner: CollaborationOwner): AppResult<CollaborationDeviceSetup?>

    suspend fun register(
        owner: CollaborationOwner,
        command: CollaborationRegistration,
    ): AppResult<CollaborationSetupReceipt>

    suspend fun retry(
        owner: CollaborationOwner,
        command: CollaborationProvisioningRetry,
    ): AppResult<CollaborationSetupReceipt>

    suspend fun bootstrap(
        owner: CollaborationOwner,
        command: CollaborationBootstrap,
    ): AppResult<CollaborationGateway>
}
