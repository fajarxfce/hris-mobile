package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryWakeRepository
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ReadCollaborationSpaceRecoveryWake(
    private val wakes: CollaborationSpaceRecoveryWakeRepository
) {
    suspend operator fun invoke(): AppResult<CollaborationSpaceRecoveryWake?> {
        currentCoroutineContext().ensureActive()
        val result = wakes.pending()
        currentCoroutineContext().ensureActive()
        return result
    }
}
