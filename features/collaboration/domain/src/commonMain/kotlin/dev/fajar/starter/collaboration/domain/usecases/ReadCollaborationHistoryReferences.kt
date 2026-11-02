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
import kotlinx.coroutines.flow.first

/** Bounded owned traversal metadata; no SDK, network, acknowledgement or decryption retry. */
class ReadCollaborationHistoryReferences(
    private val traversal: CollaborationHistoryTraversalRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        key: CollaborationHistoryTraversal,
        after: String? = null,
        limit: Int = 20,
    ): AppResult<CollaborationHistoryReferences> =
        invoke(CollaborationLocalScope.Selected(workspaceId), key, after, limit)

    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        key: CollaborationHistoryTraversal,
        after: String? = null,
        limit: Int = 20,
    ): AppResult<CollaborationHistoryReferences> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationHistoryTraversal(key) ||
                limit !in 1..20 ||
                !validCollaborationHistoryReferenceCursor(after)
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_history",
                FailureKind.Validation,
            )
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        val loaded = traversal.observeReferences(access, key, after, limit).first()
        val page =
            (loaded as? AppResult.Success)
                ?.value
                ?.takeIf { it.events.size <= limit }
                ?.let { it.copy(events = it.events.toList()) }
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (page == null || !validCollaborationPendingHistoryReferences(page, after, limit))
            return collaborationSetupFailure("client.collaboration_history_unresolved")
        return AppResult.Success(page)
    }

    private suspend fun capture(scope: CollaborationLocalScope) =
        sessions.captureMessageAccess(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(access)
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != access)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }
}
