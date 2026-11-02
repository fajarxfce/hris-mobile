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

/** Reads owned private input for review; no token admission, send or selected workspace. */
class ReadCollaborationNotificationReply(
    private val inputs: CollaborationNotificationReplyRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String): AppResult<CollaborationNotificationReplyInput?> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReplyId(id)) return unavailable(FailureKind.Validation)
        return withTimeoutOrNull(10_000) { read(id) } ?: unavailable(FailureKind.Timeout)
    }

    private suspend fun read(id: String): AppResult<CollaborationNotificationReplyInput?> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val result =
            inputs.get(
                CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId),
                id,
            )
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (result is AppResult.Failed) return result
        val input = (result as AppResult.Success).value ?: return AppResult.Success(null)
        if (
            !validCollaborationNotificationReplyInput(input) ||
                input.id != id ||
                input.candidate.parent.audience != parent.audience ||
                input.candidate.parent.accountId != parent.accountId
        )
            return unavailable(FailureKind.Conflict)
        val scope =
            CollaborationLocalScope.Captured(
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    input.candidate.action.target.companyId,
                ),
                parent.nativeSessionId,
            )
        sessions
            .checkReadStateContinuation(
                scope,
                scope.access,
                lists,
                workspaces,
                server,
                build,
                clock,
            )
            .let { if (it is AppResult.Failed) return it }
        // An absent conversation or old native family must not erase privately retained text.
        // This snapshot grants neither a new send nor an enduring content/display lease.
        return AppResult.Success(input)
    }

    private fun unavailable(kind: FailureKind) =
        collaborationSetupFailure("client.collaboration_notification_reply_read_unavailable", kind)
}
