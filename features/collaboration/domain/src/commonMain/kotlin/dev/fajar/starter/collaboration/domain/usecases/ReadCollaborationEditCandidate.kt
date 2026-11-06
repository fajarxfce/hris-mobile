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

/** The editor reads owned local facts; no SDK/device/credential acquisition or draft mutation. */
class ReadCollaborationEditCandidate(
    private val targets: CollaborationEditTargetRepository,
    private val identities: CollaborationIdentityRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        reference: CollaborationMessageReference,
    ): AppResult<CollaborationEditCandidate> {
        if (
            !validCollaborationMessageId(conversationId) ||
                !validCollaborationMessageReference(reference)
        )
            return unavailable(FailureKind.Validation)
        guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        val direct = lists.get(expected, conversationId)
        guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        if (direct is AppResult.Failed) return direct
        if (
            !ownsCollaborationReadConversation(
                expected,
                conversationId,
                (direct as AppResult.Success).value,
            )
        )
            return unavailable()
        val identity = identities.current(expected)
        guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        if (identity is AppResult.Failed) return identity
        val sender = (identity as AppResult.Success).value?.matrixUserId ?: return unavailable()
        if (!validCollaborationHistorySender(sender) || sender != reference.sender)
            return unavailable()
        val read = targets.getEditCandidate(expected, conversationId, roomId, reference.eventId)
        guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val candidate = (read as AppResult.Success).value ?: return unavailable()
        val target = candidate.target
        if (
            target.roomId != roomId ||
                target.eventId != reference.eventId ||
                target.originalLogicalId != reference.logicalId ||
                !validCollaborationText(candidate.text) ||
                !canAuthorCollaborationEdit(target, sender, clock.now().toEpochMilliseconds())
        )
            return unavailable()
        return AppResult.Success(candidate)
    }

    private suspend fun guard(
        workspaceId: String,
        expected: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(expected)
        val current = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun unavailable(kind: FailureKind = FailureKind.Conflict) =
        collaborationSetupFailure("client.collaboration_edit_unavailable", kind)
}
