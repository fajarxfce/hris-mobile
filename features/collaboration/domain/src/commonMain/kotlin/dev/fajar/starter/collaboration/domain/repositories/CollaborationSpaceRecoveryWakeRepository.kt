package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRecoveryWake
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceRecoveryWakeRepository {
    /** Null means no pending wake. Account and message state have separate owners. */
    suspend fun pending(): AppResult<CollaborationSpaceRecoveryWake?>

    /** Commits a fresh, non-reused generation before any platform scheduling. */
    suspend fun request(): AppResult<CollaborationSpaceRecoveryWake>

    /** False means a newer generation, previous acknowledgment or absent record. */
    suspend fun acknowledge(wake: CollaborationSpaceRecoveryWake): AppResult<Boolean>
}
