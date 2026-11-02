package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationRecoveryProgressRepository {
    suspend fun current(
        access: CollaborationRecoveryAccess
    ): AppResult<CollaborationRecoveryProgress?>

    /**
     * Null means the expected generation or local account owner changed. Never silently retries.
     */
    suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationRecoveryProgress?,
        sweep: CollaborationRecoverySweep?,
    ): AppResult<CollaborationRecoveryProgress?>
}
