package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Bounded local priority hints plus a durable full sweep; packet data never admits content. */
class QueueCollaborationPushSignal(
    private val sessions: SessionRepository,
    private val schedules: SyncScheduleRepository,
    private val wakes: CollaborationRecoveryWakeRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val priorities: CollaborationPushPriorityRepository,
    private val clock: Clock = Clock.System,
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
            // Priority is optional acceleration. Its failure must not prevent the full sweep.
            withTimeoutOrNull(750) { retain(session, signal.hint) }
            currentCoroutineContext().ensureActive()
            val priorityGuard = sessions.guardPushParent(server, build, parent(session))
            if (priorityGuard is AppResult.Failed) return@withTimeoutOrNull priorityGuard
            val scheduled =
                withTimeoutOrNull(1_500) { schedules.request(CollaborationRecoveryWake.WORK_KEY) }
                    ?: AppResult.Failed(
                        Failure(
                            FailureKind.Timeout,
                            "Could not request chat recovery.",
                            code = "client.collaboration_recovery_schedule_timeout",
                        )
                    )
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

    private suspend fun retain(session: Session, hint: CollaborationPushHint) {
        val parent = parent(session)
        repeat(3) {
            val loaded = priorities.current(parent)
            currentCoroutineContext().ensureActive()
            if (loaded is AppResult.Failed) return
            val before = (loaded as AppResult.Success).value
            if (before != null && !validCollaborationPushPriority(before)) return
            val next =
                nextCollaborationPushHints(before?.hints.orEmpty(), hint, clock.now().epochSeconds)
            if (next == before?.hints.orEmpty()) return
            if (sessions.guardPushParent(server, build, parent) is AppResult.Failed) return
            val saved = priorities.replace(parent, before, next)
            currentCoroutineContext().ensureActive()
            if (saved is AppResult.Failed || (saved as AppResult.Success).value != null) return
        }
    }

    private fun parent(session: Session) =
        CollaborationNativeParent(
            session.id,
            session.binding.audience,
            session.user.id,
            session.binding.serverId,
        )

    private fun identity(session: Session?) =
        session?.let { listOf(it.id, it.binding.audience, it.user.id, it.binding.serverId) }

    private fun failure(code: String, kind: FailureKind): AppResult<Nothing> =
        AppResult.Failed(Failure(kind, "Chat recovery could not be scheduled.", code = code))
}
