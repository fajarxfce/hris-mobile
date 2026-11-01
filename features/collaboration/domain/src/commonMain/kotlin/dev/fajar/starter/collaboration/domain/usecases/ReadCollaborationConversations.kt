package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationConversationRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit bounded discovery. This never opens an SDK session or starts provisioning. */
class ReadCollaborationConversations(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val conversations: CollaborationConversationRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend fun participants(
        workspaceId: String,
        query: String = "",
        after: String? = null,
        limit: Int = 30,
    ) = read(workspaceId) { conversations.participants(it, query, after, limit) }

    suspend fun list(workspaceId: String, after: String? = null, limit: Int = 30) =
        read(workspaceId) { conversations.list(it, after, limit) }

    suspend fun get(workspaceId: String, conversationId: String) =
        read(workspaceId) { conversations.get(it, conversationId) }

    suspend fun summaries(workspaceId: String, after: String? = null, limit: Int = 30) =
        read(workspaceId) { conversations.summaries(it, after, limit) }

    suspend fun summary(workspaceId: String, conversationId: String) =
        read(workspaceId) { conversations.summary(it, conversationId) }

    suspend fun room(workspaceId: String, conversationId: String) =
        read(workspaceId) { conversations.room(it, conversationId) }

    private suspend fun <T> read(
        workspaceId: String,
        acquire: suspend (CollaborationOwner) -> AppResult<T>,
    ): AppResult<CollaborationRead<T>> {
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val result = acquire(owner)
        currentCoroutineContext().ensureActive()
        val admitted = requireCollaborationOwner(capture(workspaceId), owner, result)
        if (admitted is AppResult.Failed) return admitted
        currentCoroutineContext().ensureActive()
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(CollaborationRead(owner, result.value))
        }
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)
}
