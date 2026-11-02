package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*

internal suspend fun requestRecoveryExecution(schedules: SyncScheduleRepository): AppResult<Unit> {
    currentCoroutineContext().ensureActive()
    val scheduled =
        withTimeoutOrNull(1_500) { schedules.request(CollaborationRecoveryWake.WORK_KEY) }
    currentCoroutineContext().ensureActive()
    return scheduled
        ?: AppResult.Failed(
            Failure(
                FailureKind.Timeout,
                "Could not request chat recovery.",
                code = "client.collaboration_recovery_schedule_timeout",
            )
        )
}
