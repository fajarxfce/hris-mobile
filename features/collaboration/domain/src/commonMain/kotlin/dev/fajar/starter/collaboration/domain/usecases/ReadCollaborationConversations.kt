package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Explicit bounded discovery. This never opens an SDK session or starts provisioning. */
class ReadCollaborationConversations(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val conversations: CollaborationConversationRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val replicas: CollaborationListRepository,
) {
    suspend fun participants(
        workspaceId: String,
        query: String = "",
        after: String? = null,
        limit: Int = 30,
    ) = participants(CollaborationOperationScope.Selected(workspaceId), query, after, limit)

    suspend fun list(workspaceId: String, after: String? = null, limit: Int = 30) =
        list(CollaborationOperationScope.Selected(workspaceId), after, limit)

    suspend fun get(workspaceId: String, conversationId: String) =
        get(CollaborationOperationScope.Selected(workspaceId), conversationId)

    suspend fun summaries(workspaceId: String, after: String? = null, limit: Int = 30) =
        summaries(CollaborationOperationScope.Selected(workspaceId), after, limit)

    suspend fun summary(workspaceId: String, conversationId: String) =
        summary(CollaborationOperationScope.Selected(workspaceId), conversationId)

    suspend fun room(workspaceId: String, conversationId: String) =
        room(CollaborationOperationScope.Selected(workspaceId), conversationId)

    suspend fun participants(
        scope: CollaborationOperationScope,
        query: String = "",
        after: String? = null,
        limit: Int = 30,
    ) =
        read(scope, ::collaborationListResponseClosesRead) {
            conversations.participants(it, query, after, limit)
        }

    suspend fun list(scope: CollaborationOperationScope, after: String? = null, limit: Int = 30) =
        read(scope, ::collaborationListResponseClosesRead) { conversations.list(it, after, limit) }

    suspend fun get(scope: CollaborationOperationScope, conversationId: String) =
        read(scope) { conversations.get(it, conversationId) }

    suspend fun summaries(
        scope: CollaborationOperationScope,
        after: String? = null,
        limit: Int = 30,
    ) =
        read(scope, ::collaborationListResponseClosesRead) {
            conversations.summaries(it, after, limit)
        }

    suspend fun summary(scope: CollaborationOperationScope, conversationId: String) =
        read(scope) { conversations.summary(it, conversationId) }

    suspend fun room(scope: CollaborationOperationScope, conversationId: String) =
        read(scope) { conversations.room(it, conversationId) }

    private suspend fun <T> read(
        scope: CollaborationOperationScope,
        closesRead: (Failure) -> Boolean = ::collaborationContentResponseClosesRead,
        acquire: suspend (CollaborationOwner) -> AppResult<T>,
    ): AppResult<CollaborationRead<T>> {
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val access = owner.localListAccess()
        val initial = replicas.admission(access)
        if (initial is AppResult.Failed) return initial
        val gate = (initial as AppResult.Success).value
        var retainedDenial = gate.denial
        fun failed(failure: Failure) =
            AppResult.Failed(
                if (retainedDenial != null && !closesRead(failure)) requireNotNull(retainedDenial)
                else failure
            )
        val before = requireCollaborationOwner(capture(scope), owner, null)
        if (before is AppResult.Failed) return failed(before.failure)
        val result = acquire(owner)
        if (result is AppResult.Failed && closesRead(result.failure)) {
            retainedDenial = result.failure
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { replicas.restrict(access, result.failure) }
            }
        }
        currentCoroutineContext().ensureActive()
        val admitted = requireCollaborationOwner(capture(scope), owner, result)
        if (admitted is AppResult.Failed) return failed(admitted.failure)
        if (result is AppResult.Failed) return failed(result.failure)
        val reopened = replicas.admit(access, gate.revision)
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(scope), owner, reopened)
        if (current is AppResult.Failed) return failed(current.failure)
        if (reopened is AppResult.Failed) return failed(reopened.failure)
        if (!(reopened as AppResult.Success).value) {
            val latest = replicas.admission(access)
            currentCoroutineContext().ensureActive()
            val finalOwner = requireCollaborationOwner(capture(scope), owner, latest)
            if (finalOwner is AppResult.Failed) return failed(finalOwner.failure)
            return when (latest) {
                is AppResult.Failed -> failed(latest.failure)
                is AppResult.Success ->
                    latest.value.denial?.let { AppResult.Failed(it) }
                        ?: failed(collaborationListConflict().failure)
            }
        }
        return AppResult.Success(CollaborationRead(owner, (result as AppResult.Success).value))
    }

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)
}
