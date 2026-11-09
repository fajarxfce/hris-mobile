package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryWakeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Persists a hint before enqueue. Success requests execution; it does not grant message access. */
class RequestCollaborationSpaceRecovery(
    private val wakes: CollaborationSpaceRecoveryWakeRepository,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val retained = wakes.request()
        currentCoroutineContext().ensureActive()
        if (retained is AppResult.Failed) return retained
        val scheduled =
            withTimeoutOrNull(1_500) { schedules.request(CollaborationSpaceRecoveryWake.WORK_KEY) }
        currentCoroutineContext().ensureActive()
        return scheduled
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Timeout,
                    "Could not request chat recovery.",
                    code = "client.collaboration_space_recovery_schedule_timeout",
                )
            )
    }
}
