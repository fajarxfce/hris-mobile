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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * Restores the end of owned message history from one finite traversal. Room-state references remain
 * stored but cannot advertise missing messages. No network, SDK or acknowledgement.
 */
class ReadCollaborationHistoryEnd(
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
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationHistoryTraversal(key))
            return collaborationSetupFailure(
                "client.collaboration_invalid_history",
                FailureKind.Validation,
            )
        val scope = CollaborationLocalScope.Selected(workspaceId)
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        var after: String? = null
        var snapshot: CollaborationHistoryProgress? = null
        var previousSequence = -1L
        while (true) {
            continuation(scope, access).let { if (it is AppResult.Failed) return it }
            val loaded = traversal.observeReferences(access, key, after, 20).first()
            val page =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.events.size <= 20 }
                    ?.let { it.copy(events = it.events.toList()) }
            continuation(scope, access).let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (page == null || !validCollaborationPendingHistoryReferences(page, after, 20))
                return collaborationSetupFailure("client.collaboration_history_unresolved")
            val progress = page.progress ?: return AppResult.Success(false)
            if (!progress.exhausted) return AppResult.Success(false)
            if (snapshot == null) snapshot = progress
            else if (!sameCollaborationHistoryExtent(snapshot, progress))
                return AppResult.Success(false)
            if (page.events.any(::collaborationHistoryNeedsOriginal))
                return AppResult.Success(false)
            if (page.events.isNotEmpty()) {
                if (page.events.first().sequence <= previousSequence)
                    return AppResult.Success(false)
                previousSequence = page.events.last().sequence
            }
            after = page.after ?: return AppResult.Success(true)
        }
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
