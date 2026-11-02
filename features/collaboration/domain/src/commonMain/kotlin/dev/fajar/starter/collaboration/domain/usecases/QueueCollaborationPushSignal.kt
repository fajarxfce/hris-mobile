package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.policies.parseCollaborationPushSignal
import dev.fajar.starter.collaboration.domain.policies.requestRecoveryExecution
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Only local ownership and a fixed durable wake; never authority from packet company/room data. */
class QueueCollaborationPushSignal(
    private val sessions: SessionRepository,
    private val schedules: SyncScheduleRepository,
    private val wakes: CollaborationRecoveryWakeRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(fields: Map<String, String>): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val signal = parseCollaborationPushSignal(fields) ?: return AppResult.Success(false)
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
            return AppResult.Success(false)
        return withTimeoutOrNull(3.seconds) {
            val loaded = sessions.current()
            currentCoroutineContext().ensureActive()
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val session =
                (loaded as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(false)
            if (
                session.binding.audience != server.audience ||
                    session.user.id != signal.accountId ||
                    session.binding.serverId != signal.nativeSessionId ||
                    session.id.length !in 1..128 ||
                    session.id.any { it.code !in 33..126 }
            )
                return@withTimeoutOrNull AppResult.Success(false)
            val retained = wakes.request()
            currentCoroutineContext().ensureActive()
            val beforeSchedule = sessions.current()
            currentCoroutineContext().ensureActive()
            if (beforeSchedule is AppResult.Failed) return@withTimeoutOrNull beforeSchedule
            if (identity((beforeSchedule as AppResult.Success).value) != identity(session))
                return@withTimeoutOrNull failure("client.session_changed", FailureKind.Unauthorized)
            if (retained is AppResult.Failed) return@withTimeoutOrNull retained
            val scheduled = requestRecoveryExecution(schedules)
            currentCoroutineContext().ensureActive()
            val after = sessions.current()
            currentCoroutineContext().ensureActive()
            if (after is AppResult.Failed) return@withTimeoutOrNull after
            if (identity((after as AppResult.Success).value) != identity(session))
                return@withTimeoutOrNull failure("client.session_changed", FailureKind.Unauthorized)
            when (scheduled) {
                is AppResult.Failed -> scheduled
                is AppResult.Success -> AppResult.Success(true)
            }
        } ?: failure("client.collaboration_push_timeout", FailureKind.Timeout)
    }

    private fun identity(session: Session?) =
        session?.let { listOf(it.id, it.binding.audience, it.user.id, it.binding.serverId) }

    private fun failure(code: String, kind: FailureKind): AppResult<Nothing> =
        AppResult.Failed(Failure(kind, "Chat recovery could not be scheduled.", code = code))
}
