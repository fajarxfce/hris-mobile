package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete only the checkpoint captured before recovery; false requires another pending read. */
class CompleteCollaborationRecoveryWake(private val wakes: CollaborationRecoveryWakeRepository) {
    suspend operator fun invoke(wake: CollaborationRecoveryWake): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val result = wakes.acknowledge(wake)
        currentCoroutineContext().ensureActive()
        return result
    }
}
