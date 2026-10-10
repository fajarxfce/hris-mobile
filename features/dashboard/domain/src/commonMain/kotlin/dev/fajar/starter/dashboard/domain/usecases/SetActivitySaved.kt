package dev.fajar.starter.dashboard.domain.usecases

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.*
import dev.fajar.starter.dashboard.domain.config.DashboardFlags
import dev.fajar.starter.dashboard.domain.entities.ActivitySaveResult
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.featureflags.domain.policy.evaluateFlag
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.security.domain.access.policy.allows
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Clock

class SetActivitySaved(
    private val repository: DashboardRepository,
    private val scheduler: SyncScheduleRepository,
    private val flags: FeatureFlagRepository,
    private val environment: AppEnvironment,
    private val sessions: SessionRepository,
    private val access: AccessRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        id: String,
        saved: Boolean,
        sessionId: String,
    ): AppResult<ActivitySaveResult> {
        if (id.isBlank())
            return AppResult.Failed(Failure(FailureKind.Validation, "Select an activity."))
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (session == null || session.id != sessionId)
            return AppResult.Failed(Failure(FailureKind.Unauthorized, "Sign in to continue."))
        val snapshot =
            when (val result = flags.snapshot()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!evaluateFlag(DashboardFlags.SavedActivities, snapshot, environment).enabled) {
            return AppResult.Failed(
                Failure(FailureKind.Unavailable, "Saving activities is currently unavailable.")
            )
        }
        val grants =
            when (val result = access.cached(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!allows(grants, Permission.SaveActivity, sessionId, clock.now().toEpochMilliseconds()))
            return AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Your account cannot perform this action.")
            )
        when (val local = repository.setSaved(sessionId, id, saved)) {
            is AppResult.Failed -> return local
            is AppResult.Success -> Unit
        }
        val request = scheduler.request(SyncDashboard.KEY)
        return AppResult.Success(ActivitySaveResult((request as? AppResult.Failed)?.failure))
    }
}
