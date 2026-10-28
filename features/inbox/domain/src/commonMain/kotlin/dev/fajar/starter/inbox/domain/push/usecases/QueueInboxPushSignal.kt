package dev.fajar.starter.inbox.domain.push.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.push.entities.pushOwner
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.inbox.domain.push.policies.parseInboxPushSignal
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The receiver performs only bounded local admission/persistence, never network or OS display. */
class QueueInboxPushSignal(
    private val sessions: SessionRepository,
    private val queue: InboxPushQueueRepository,
    private val schedules: SyncScheduleRepository,
    private val server: IdentityServer,
    private val clock: Clock,
    private val mutations: Mutex,
) {
    /** True means an owned pending hint exists and its worker should be enqueued. */
    suspend operator fun invoke(fields: Map<String, String>): AppResult<Boolean> {
        val signal = parseInboxPushSignal(fields) ?: return AppResult.Success(false)
        return withTimeoutOrNull(3.seconds) {
            val queued = mutations.withLock { enqueue(signal) }
            if (queued is AppResult.Failed || !(queued as AppResult.Success).value)
                return@withTimeoutOrNull queued
            when (val scheduled = schedules.request(KEY)) {
                is AppResult.Failed -> scheduled
                is AppResult.Success -> queued
            }
        } ?: failed("client.inbox_push_timeout", FailureKind.Timeout)
    }

    companion object {
        const val KEY = "hris.inbox-push"
    }

    private suspend fun enqueue(signal: InboxPushSignal): AppResult<Boolean> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(false)
            }
        if (session.user.id != signal.accountId || session.binding.audience != server.audience)
            return AppResult.Success(false)
        val owner = InboxPushOwner(server.audience, session.user.id)
        val stored =
            when (val result = queue.read()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val current = guard(session)
        if (current is AppResult.Failed) return current
        val now = clock.now()
        val owned =
            stored.takeIf { it.owner == owner } ?: InboxPushQueue(owner, attempts = stored.attempts)
        val retained =
            owned.copy(
                pending = owned.pending.filter { it.receivedAt > now - 1.days },
                attempts = owned.attempts.filter { it.attemptedAt > now - 7.days },
            )
        if (
            retained.attempts.any {
                it.eventId == signal.eventId &&
                    it.companyId == signal.companyId &&
                    it.owner == owner
            }
        )
            return AppResult.Success(false)
        val duplicate = retained.pending.singleOrNull { it.eventId == signal.eventId }
        if (duplicate != null)
            return AppResult.Success(
                duplicate.companyId == signal.companyId && duplicate.inboxId == signal.inboxId
            )
        if (retained.pending.size >= 64 || retained.pending.size + retained.attempts.size >= 512)
            return failed("client.inbox_push_capacity", FailureKind.Conflict)
        val updated =
            retained.copy(
                pending =
                    retained.pending +
                        QueuedInboxPush(signal.eventId, signal.companyId, signal.inboxId, now)
            )
        val before = guard(session)
        if (before is AppResult.Failed) return before
        val saved = queue.compareAndSet(stored, updated)
        val after = guard(session)
        if (after is AppResult.Failed) return after
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                if (saved.value) AppResult.Success(true)
                else failed("client.inbox_push_changed", FailureKind.Conflict)
        }
    }

    private suspend fun guard(session: Session): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return when (val current = sessions.current()) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                if (current.value?.pushOwner() == session.pushOwner()) AppResult.Success(Unit)
                else failed("client.session_changed", FailureKind.Unauthorized)
        }
    }

    private fun failed(code: String, kind: FailureKind) =
        AppResult.Failed(Failure(kind, "Inbox update could not be retained.", code = code))
}
