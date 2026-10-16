package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.InboxAccess
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.inbox.domain.repositories.InboxRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class LoadUnreadInbox(
    private val remote: InboxRepository,
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) {
    suspend operator fun invoke(workspaceId: String): AppResult<Long> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val admitted = readContinuation(workspaceId, access)
        if (admitted is AppResult.Failed) return admitted
        val count = remote.unreadCount(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val current =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (!sameInboxScope(access, current))
            return inboxCommandFailure("client.inbox_scope_changed", FailureKind.AccessDenied)
        if (count is AppResult.Failed && inboxFailureInvalidatesCache(count.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, count.failure) }
            currentCoroutineContext().ensureActive()
            val owner = validateInboxCommandAccess(currentAccess(workspaceId), access)
            return if (owner is AppResult.Failed) owner else count
        }
        val continuation = readContinuation(workspaceId, access)
        return if (continuation is AppResult.Failed) continuation else count
    }

    private suspend fun readContinuation(
        workspaceId: String,
        access: InboxAccess,
    ): AppResult<Unit> {
        val current = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (current is AppResult.Failed) return current
        val admission = cache.admission(access)
        currentCoroutineContext().ensureActive()
        val owner = validateInboxCommandAccess(currentAccess(workspaceId), access)
        if (owner is AppResult.Failed) return owner
        return when (admission) {
            is AppResult.Failed -> admission
            is AppResult.Success -> admission.value.denial?.let { AppResult.Failed(it) } ?: owner
        }
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<InboxAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return inboxCommandFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitInbox(workspaceId, workspace, latest)
    }
}
