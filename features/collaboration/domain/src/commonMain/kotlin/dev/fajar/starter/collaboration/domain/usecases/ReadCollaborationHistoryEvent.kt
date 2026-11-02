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

/** One retained original alias or confirmed tombstone; no provider request or display authority. */
class ReadCollaborationHistoryEvent(
    private val history: CollaborationHistoryRepository,
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
        eventId: String,
    ): AppResult<CollaborationHistoryEventFact?> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationHistoryTraversal(key) || !validCollaborationEventId(eventId))
            return collaborationSetupFailure(
                "client.collaboration_invalid_history",
                FailureKind.Validation,
            )
        val captured = capture(scope)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        val loaded = history.getEventFact(access, key.conversationId, key.roomId, eventId)
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val fact = (loaded as AppResult.Success).value
        if (
            fact != null &&
                (fact.eventId != eventId ||
                    !validCollaborationHistorySender(fact.sender) ||
                    (fact.timestamp == null && !fact.redacted) ||
                    (fact.timestamp != null && fact.timestamp !in 0..9_007_199_254_740_991L))
        )
            return collaborationSetupFailure("client.collaboration_invalid_history_fact")
        return AppResult.Success(fact)
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
