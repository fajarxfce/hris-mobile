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

class ReadCollaborationThreadContext(
    private val conversations: CollaborationListRepository,
    private val identities: CollaborationIdentityRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        conversationId: String,
    ): AppResult<CollaborationRead<CollaborationThreadContext>> {
        if (!validCollaborationMessageId(conversationId)) return failed()
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        continuation(workspaceId, owner).let { if (it is AppResult.Failed) return it }
        val stored = conversations.get(owner.localListAccess(), conversationId)
        continuation(workspaceId, owner).let { if (it is AppResult.Failed) return it }
        if (stored is AppResult.Failed) return stored
        val summary = (stored as AppResult.Success).value ?: return failed()
        val direct = summary.conversation
        val peer =
            when (owner.accountId) {
                direct.firstAccountId -> direct.secondAccountId
                direct.secondAccountId -> direct.firstAccountId
                else -> null
            }
        if (
            direct.id != conversationId ||
                direct.companyId != owner.companyId ||
                peer == null ||
                peer == owner.accountId ||
                summary.peer.accountId != peer
        )
            return failed()
        val known = identities.current(owner.localListAccess())
        continuation(workspaceId, owner).let { if (it is AppResult.Failed) return it }
        if (known is AppResult.Failed) return known
        val self = (known as AppResult.Success).value
        if (self != null && !validCollaborationHistorySender(self.matrixUserId)) return failed()
        return AppResult.Success(
            CollaborationRead(owner, CollaborationThreadContext(summary, self))
        )
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationLocalOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(workspaceId), owner, null)
        if (current is AppResult.Failed) return current
        val gate = conversations.admission(owner.localListAccess())
        currentCoroutineContext().ensureActive()
        val checked = requireCollaborationOwner(capture(workspaceId), owner, gate)
        if (checked is AppResult.Failed) return checked
        return when (gate) {
            is AppResult.Failed -> gate
            is AppResult.Success ->
                gate.value.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
    }

    private fun failed() =
        collaborationSetupFailure(
            "client.collaboration_conversation_unavailable",
            FailureKind.Unavailable,
        )
}
