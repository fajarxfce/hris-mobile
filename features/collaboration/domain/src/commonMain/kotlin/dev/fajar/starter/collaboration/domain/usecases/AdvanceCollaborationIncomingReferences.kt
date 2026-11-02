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

/**
 * Owned incoming seek progression after one attempted page; no network or content acknowledgement.
 */
class AdvanceCollaborationIncomingReferences(
    private val traversal: CollaborationHistoryTraversalRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        key: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        retryRequired: Boolean,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (
            key.purpose != CollaborationHistoryPurpose.Incoming ||
                !validCollaborationHistoryTraversal(key) ||
                source.events.size > 20
        )
            return invalid()
        val page = source.copy(events = source.events.toList())
        val position = page.progress?.incoming ?: return invalid()
        if (!validCollaborationPendingHistoryReferences(page, position.pendingAfter, 20))
            return invalid()
        val through = position.pendingThrough ?: page.progress.references
        if (
            page.events.any { it.sequence >= through } ||
                (page.after != null && page.events.last().sequence + 1 >= through)
        )
            return invalid()
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        val saved = traversal.advanceIncomingReferences(access, key, page, retryRequired)
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        return if ((saved as AppResult.Success).value) AppResult.Success(Unit) else failed()
    }

    private fun failed() =
        collaborationSetupFailure("client.collaboration_history_progress_changed")

    private fun invalid() =
        collaborationSetupFailure("client.collaboration_invalid_history", FailureKind.Validation)

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
