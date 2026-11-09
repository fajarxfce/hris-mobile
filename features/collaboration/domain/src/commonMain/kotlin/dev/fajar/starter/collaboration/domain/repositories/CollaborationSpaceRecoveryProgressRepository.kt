package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceRecoveryProgressRepository {
    suspend fun current(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceRecoveryProgress?>

    /**
     * Null means the exact checkpoint or local owner changed. Never retries or acknowledges work.
     */
    suspend fun replace(
        owner: CollaborationSpaceOwner,
        expected: CollaborationSpaceRecoveryProgress?,
        sweep: CollaborationSpaceRecoverySweep?,
    ): AppResult<CollaborationSpaceRecoveryProgress?>
}
