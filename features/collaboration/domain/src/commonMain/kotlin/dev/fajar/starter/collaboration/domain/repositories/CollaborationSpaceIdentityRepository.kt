package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceIdentityRepository {
    /**
     * Reads already selected user metadata. Null never selects or proves no original SDK exists.
     */
    suspend fun read(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceIdentityObservation>

    /** The owning use case must retain this exact command before invoking transport. */
    suspend fun select(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceIdentityCommand,
    ): AppResult<CollaborationSetupReceipt>
}
