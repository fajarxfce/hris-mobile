package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceIdentityRepository {
    /** The owning use case must retain this exact command before invoking transport. */
    suspend fun select(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceIdentityCommand,
    ): AppResult<CollaborationSetupReceipt>
}
