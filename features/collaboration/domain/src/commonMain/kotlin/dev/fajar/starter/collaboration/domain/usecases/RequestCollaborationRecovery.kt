package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.policies.requestRecoveryExecution
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Persists a hint before enqueue. Success requests execution; it does not grant message access. */
class RequestCollaborationRecovery(
    private val wakes: CollaborationRecoveryWakeRepository,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val retained = wakes.request()
        currentCoroutineContext().ensureActive()
        if (retained is AppResult.Failed) return retained
        return requestRecoveryExecution(schedules)
    }
}
