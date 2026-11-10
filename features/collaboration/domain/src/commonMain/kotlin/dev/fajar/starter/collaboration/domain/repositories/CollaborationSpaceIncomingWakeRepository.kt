package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingWake
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceIncomingWakeRepository {
    suspend fun pending(): AppResult<CollaborationSpaceIncomingWake?>

    suspend fun request(): AppResult<CollaborationSpaceIncomingWake>

    suspend fun acknowledge(wake: CollaborationSpaceIncomingWake): AppResult<Boolean>
}
