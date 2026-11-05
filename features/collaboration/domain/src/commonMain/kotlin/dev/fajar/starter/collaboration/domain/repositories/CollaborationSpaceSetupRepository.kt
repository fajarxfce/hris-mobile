package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One native request. The caller owns durable intent, admission, deadlines and SDK adoption. */
interface CollaborationSpaceSetupRepository {
    suspend fun retry(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceProvisioningRetry,
    ): AppResult<CollaborationSetupReceipt>

    suspend fun bootstrap(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceBootstrap,
    ): AppResult<CollaborationSpaceGateway>
}
