package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.common.result.AppResult

interface CollaborationRecoveryWakeRepository {
    /** Null means no pending wake. Account and message state have separate owners. */
    suspend fun pending(): AppResult<CollaborationRecoveryWake?>

    /** Commits a fresh, non-reused generation before any platform scheduling. */
    suspend fun request(): AppResult<CollaborationRecoveryWake>

    /** False means a newer generation, previous acknowledgment or absent record. */
    suspend fun acknowledge(wake: CollaborationRecoveryWake): AppResult<Boolean>
}
