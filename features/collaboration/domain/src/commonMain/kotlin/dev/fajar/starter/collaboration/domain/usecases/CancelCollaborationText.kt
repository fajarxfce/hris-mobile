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

/**
 * Explicit local cancellation, admitted against the caller's captured owner and full queued row.
 */
class CancelCollaborationText(
    private val cancellations: CollaborationMessageCancellationRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expectedAccess: CollaborationLocalAccess,
        expected: CollaborationMessageRecord,
    ): AppResult<Unit> {
        if (!validCollaborationCancellation(expected))
            return collaborationSetupFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        val captured = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (access != expectedAccess || expected.intent.owner != access.messageOwner())
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        currentCoroutineContext().ensureActive()
        val result = cancellations.cancel(access, expected)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (result is AppResult.Failed) return result
        return if ((result as AppResult.Success).value) AppResult.Success(Unit)
        else
            collaborationSetupFailure(
                "client.collaboration_cancellation_unavailable",
                FailureKind.Conflict,
            )
    }

    private suspend fun guard(
        workspaceId: String,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(access)
        val current = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
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
