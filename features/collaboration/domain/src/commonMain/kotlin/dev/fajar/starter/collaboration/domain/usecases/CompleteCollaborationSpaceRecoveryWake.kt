package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryWakeRepository
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete only the checkpoint captured before recovery; false requires another pending read. */
class CompleteCollaborationSpaceRecoveryWake(
    private val wakes: CollaborationSpaceRecoveryWakeRepository
) {
    suspend operator fun invoke(wake: CollaborationSpaceRecoveryWake): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val result = wakes.acknowledge(wake)
        currentCoroutineContext().ensureActive()
        return result
    }
}
