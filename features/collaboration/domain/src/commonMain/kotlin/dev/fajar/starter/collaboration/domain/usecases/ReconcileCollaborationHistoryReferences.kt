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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Offline projection acknowledgement from retained originals/tombstones; no SDK or retry. */
class ReconcileCollaborationHistoryReferences(
    private val traversal: CollaborationHistoryTraversalRepository,
    private val history: CollaborationHistoryRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        key: CollaborationHistoryTraversal,
        after: String? = null,
        limit: Int = 20,
    ): AppResult<CollaborationHistoryReconciliation> =
        invoke(CollaborationLocalScope.Selected(workspaceId), key, after, limit)

    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        key: CollaborationHistoryTraversal,
        after: String? = null,
        limit: Int = 20,
    ): AppResult<CollaborationHistoryReconciliation> =
        execution.withLock {
            currentCoroutineContext().ensureActive()
            if (
                !validCollaborationHistoryTraversal(key) ||
                    limit !in 1..20 ||
                    !validCollaborationHistoryReferenceCursor(after)
            )
                return@withLock failed()
            val captured = capture(scope)
            if (captured is AppResult.Failed) return@withLock captured
            val access = (captured as AppResult.Success).value
            continuation(scope, access).let { if (it is AppResult.Failed) return@withLock it }
            val read = traversal.observeReferences(access, key, after, limit).first()
            // Detach the bounded list before another suspension can mutate caller-owned values.
            val page =
                (read as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.events.size <= limit }
                    ?.let { it.copy(events = it.events.toList()) }
            continuation(scope, access).let { if (it is AppResult.Failed) return@withLock it }
            if (read is AppResult.Failed) return@withLock read
            if (page == null || !validCollaborationPendingHistoryReferences(page, after, limit))
                return@withLock failed()
            val resolved = mutableListOf<CollaborationHistoryReference>()
            for (reference in page.events) {
                val found =
                    history.getEventFact(
                        access,
                        key.conversationId,
                        key.roomId,
                        reference.event.eventId,
                    )
                continuation(scope, access).let { if (it is AppResult.Failed) return@withLock it }
                if (found is AppResult.Failed) return@withLock found
                val fact = (found as AppResult.Success).value ?: continue
                val event = reference.event
                val timestamp = fact.timestamp
                if (
                    fact.eventId != event.eventId ||
                        fact.sender != event.sender ||
                        (timestamp == null && !fact.redacted) ||
                        (timestamp != null && timestamp != event.timestamp)
                )
                    return@withLock failed()
                resolved += reference
            }
            if (resolved.isNotEmpty()) {
                val saved = traversal.resolve(access, key, requireNotNull(page.progress), resolved)
                continuation(scope, access).let { if (it is AppResult.Failed) return@withLock it }
                if (saved is AppResult.Failed) return@withLock saved
                if (!(saved as AppResult.Success).value) return@withLock failed()
            }
            AppResult.Success(
                CollaborationHistoryReconciliation(
                    resolved.size,
                    page.events.size - resolved.size,
                    page.after,
                )
            )
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

    private fun failed() = collaborationSetupFailure("client.collaboration_history_unresolved")
}
