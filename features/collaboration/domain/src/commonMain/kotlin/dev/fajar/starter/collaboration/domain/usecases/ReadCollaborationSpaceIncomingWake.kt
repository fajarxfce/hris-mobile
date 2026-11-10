package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIncomingWakeRepository
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ReadCollaborationSpaceIncomingWake(
    private val wakes: CollaborationSpaceIncomingWakeRepository
) {
    suspend operator fun invoke(): AppResult<CollaborationSpaceIncomingWake?> {
        currentCoroutineContext().ensureActive()
        val result = wakes.pending()
        currentCoroutineContext().ensureActive()
        return result
    }
}
