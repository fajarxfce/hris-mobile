package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIncomingWakeRepository
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A running periodic/startup consumer retains a hint without recursively enqueueing itself. */
class EnsureCollaborationSpaceIncomingWake(
    private val wakes: CollaborationSpaceIncomingWakeRepository
) {
    suspend operator fun invoke(): AppResult<CollaborationSpaceIncomingWake> {
        currentCoroutineContext().ensureActive()
        val pending = wakes.pending()
        currentCoroutineContext().ensureActive()
        if (pending is AppResult.Failed) return pending
        (pending as AppResult.Success).value?.let {
            return AppResult.Success(it)
        }
        val created = wakes.request()
        currentCoroutineContext().ensureActive()
        return created
    }
}
