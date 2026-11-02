package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.policies.requestRecoveryExecution
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Repairs a retained write/enqueue gap without replacing or acknowledging its generation. */
class ResumeCollaborationRecovery(
    private val wakes: CollaborationRecoveryWakeRepository,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val pending = wakes.pending()
        currentCoroutineContext().ensureActive()
        if (pending is AppResult.Failed) return pending
        if ((pending as AppResult.Success).value == null) return AppResult.Success(false)
        val scheduled = requestRecoveryExecution(schedules)
        if (scheduled is AppResult.Failed) return scheduled
        return AppResult.Success(true)
    }
}
