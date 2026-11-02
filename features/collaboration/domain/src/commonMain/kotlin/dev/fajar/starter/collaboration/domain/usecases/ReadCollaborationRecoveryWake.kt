package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ReadCollaborationRecoveryWake(private val wakes: CollaborationRecoveryWakeRepository) {
    suspend operator fun invoke(): AppResult<CollaborationRecoveryWake?> {
        currentCoroutineContext().ensureActive()
        val result = wakes.pending()
        currentCoroutineContext().ensureActive()
        return result
    }
}
