package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Finite use-case context, never retained by a repository or application singleton. */
internal class ReviewedReplyAccess
private constructor(
    val parent: CollaborationNativeParent,
    val access: CollaborationLocalAccess,
    private val scope: CollaborationLocalScope,
    private val sessions: SessionRepository,
    private val lists: CollaborationListRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    val recovery = CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)

    suspend fun guard(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val current =
            sessions.checkReadStateContinuation(
                scope,
                access,
                lists,
                workspaces,
                server,
                build,
                clock,
            )
        if (current is AppResult.Failed) return current
        return sessions.guardPushParent(server, build, parent)
    }

    suspend fun <T> checked(operation: suspend () -> AppResult<T>): AppResult<T> {
        guard().let { if (it is AppResult.Failed) return it }
        val result = operation()
        guard().let { if (it is AppResult.Failed) return it }
        return result
    }

    companion object {
        suspend fun capture(
            expected: CollaborationNotificationReplyInput,
            sessions: SessionRepository,
            lists: CollaborationListRepository,
            workspaces: WorkspaceRepository,
            server: IdentityServer,
            build: AppBuild,
            clock: Clock,
            workspaceId: String? = null,
            expectedAccess: CollaborationLocalAccess? = null,
            expectedParent: CollaborationNativeParent? = null,
        ): AppResult<ReviewedReplyAccess> {
            val read = sessions.capturePushParent(server, build)
            if (read is AppResult.Failed) return read
            val parent = (read as AppResult.Success).value
            val candidate = expected.candidate
            val access =
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    candidate.action.target.companyId,
                )
            if (
                candidate.parent.audience != parent.audience ||
                    candidate.parent.accountId != parent.accountId ||
                    (expectedAccess != null && expectedAccess != access) ||
                    (expectedParent != null && expectedParent != parent)
            )
                return reviewedReplyFailure(FailureKind.Unauthorized)
            val scope =
                workspaceId?.let { CollaborationLocalScope.Selected(it) }
                    ?: CollaborationLocalScope.Captured(access, parent.nativeSessionId)
            val captured =
                ReviewedReplyAccess(
                    parent,
                    access,
                    scope,
                    sessions,
                    lists,
                    workspaces,
                    server,
                    build,
                    clock,
                )
            captured.guard().let { if (it is AppResult.Failed) return it }
            return AppResult.Success(captured)
        }
    }
}

/** Draft versions may advance, but no other document or original can replace the editor's input. */
internal fun sameReviewedReplyDocument(
    expected: CollaborationNotificationReplyInput,
    found: CollaborationNotificationReplyInput,
) =
    expected.id == found.id &&
        expected.sequence == found.sequence &&
        expected.version.generation == found.version.generation &&
        found.version.revision >= expected.version.revision &&
        expected.candidate == found.candidate

internal fun reviewedReplyFailure(kind: FailureKind) =
    collaborationSetupFailure("client.collaboration_reviewed_reply_unavailable", kind)
