package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceIncomingWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIncomingWakeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Repairs a retained write/enqueue gap without replacing or acknowledging its generation. */
class ResumeCollaborationSpaceIncomingRecovery(
    private val wakes: CollaborationSpaceIncomingWakeRepository,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val pending = wakes.pending()
        currentCoroutineContext().ensureActive()
        if (pending is AppResult.Failed) return pending
        if ((pending as AppResult.Success).value == null) return AppResult.Success(false)
        val scheduled =
            withTimeoutOrNull(1_500) { schedules.request(CollaborationSpaceIncomingWake.WORK_KEY) }
        currentCoroutineContext().ensureActive()
        if (scheduled == null)
            return AppResult.Failed(
                Failure(
                    FailureKind.Timeout,
                    "Could not request chat recovery.",
                    code = "client.collaboration_space_incoming_schedule_timeout",
                )
            )
        if (scheduled is AppResult.Failed) return scheduled
        return AppResult.Success(true)
    }
}
