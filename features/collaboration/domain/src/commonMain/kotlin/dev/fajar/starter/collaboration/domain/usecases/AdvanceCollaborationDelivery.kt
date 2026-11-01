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

/** Persists the turn before I/O so process death cannot keep selecting the same failing room. */
class AdvanceCollaborationDelivery(
    private val cursors: CollaborationDeliveryCursorRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        work: CollaborationDeliveryWork,
        conversationId: String,
    ): AppResult<CollaborationDeliveryCursor> {
        if (!validCollaborationMessageId(conversationId) || conversationId !in work.conversationIds)
            return collaborationSetupFailure(
                "client.collaboration_invalid_delivery",
                FailureKind.Validation,
            )
        continuation(work).let { if (it is AppResult.Failed) return it }
        val saved = cursors.advance(work.access, work.cursor, conversationId)
        continuation(work).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        val cursor =
            (saved as AppResult.Success).value
                ?: return collaborationSetupFailure("client.collaboration_delivery_changed")
        if (
            !validCollaborationMessageId(cursor.generation) ||
                cursor.afterConversationId != conversationId ||
                cursor == work.cursor
        )
            return collaborationSetupFailure("client.collaboration_delivery_changed")
        return AppResult.Success(cursor)
    }

    private suspend fun continuation(work: CollaborationDeliveryWork): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(work.access)
        val captured =
            sessions.captureMessageAccess(work.workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (captured is AppResult.Failed) return captured
        if ((captured as AppResult.Success).value != work.access)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }
}
