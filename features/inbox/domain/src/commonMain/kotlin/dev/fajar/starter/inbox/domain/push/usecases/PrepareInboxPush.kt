package dev.fajar.starter.inbox.domain.push.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.InboxAccess
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.push.entities.*
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A durable hint receives at most three network preparations during its one-day lifetime. */
class PrepareInboxPush(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val queue: InboxPushQueueRepository,
    private val server: IdentityServer,
    private val clock: Clock,
    private val mutations: Mutex,
) {
    suspend operator fun invoke(): AppResult<PreparedInboxPush?> =
        withTimeoutOrNull(5.seconds) { mutations.withLock { prepare() } }
            ?: inboxCommandFailure("client.inbox_push_timeout", FailureKind.Timeout)

    private suspend fun prepare(): AppResult<PreparedInboxPush?> {
        val stored =
            when (val result = queue.read()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (stored.pending.isEmpty()) return AppResult.Success(null)
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(null)
            }
        if (
            session.binding.audience != server.audience ||
                stored.owner != InboxPushOwner(server.audience, session.user.id)
        )
            return AppResult.Success(null)
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(null)
            }
        val access =
            when (val result = currentAccess(workspace.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (
            access.accountId != stored.owner?.accountId || access.audience != stored.owner?.audience
        )
            return inboxCommandFailure("client.inbox_scope_changed", FailureKind.AccessDenied)
        val now = clock.now()
        if (access.sessionExpiresAt <= now || access.workspaceExpiresAt <= now)
            return inboxCommandFailure("client.authentication_required", FailureKind.Unauthorized)
        val retained =
            stored.copy(
                pending = stored.pending.filter { it.receivedAt > now - 1.days },
                attempts = stored.attempts.filter { it.attemptedAt > now - 7.days },
            )
        val hint =
            retained.pending.firstOrNull { it.companyId == access.companyId && it.preparations < 3 }
        val reserved = hint?.copy(preparations = hint.preparations + 1)
        val updated =
            if (reserved == null) retained
            else retained.copy(pending = retained.pending.map { if (it == hint) reserved else it })
        val before = validateInboxCommandAccess(currentAccess(workspace.id), access)
        if (before is AppResult.Failed) return before
        if (updated != stored) {
            when (val saved = queue.compareAndSet(stored, updated)) {
                is AppResult.Failed -> return saved
                is AppResult.Success ->
                    if (!saved.value) return inboxCommandFailure("client.inbox_push_changed")
            }
        }
        val after = validateInboxCommandAccess(currentAccess(workspace.id), access)
        if (after is AppResult.Failed) return after
        return AppResult.Success(reserved?.let { PreparedInboxPush(workspace.id, access, it, now) })
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<InboxAccess> {
        currentCoroutineContext().ensureActive()
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return inboxCommandFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val current =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitInbox(workspaceId, workspace, current)
    }
}
