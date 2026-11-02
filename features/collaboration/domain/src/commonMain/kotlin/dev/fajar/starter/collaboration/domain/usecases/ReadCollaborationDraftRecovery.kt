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

/** Bounded indexed discovery without credentials, SDK acquisition or implicit queue admission. */
class ReadCollaborationDraftRecovery(
    private val drafts: CollaborationDraftRepository,
    private val cursors: CollaborationDraftRecoveryCursorRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(): AppResult<CollaborationDraftRecoveryWork?> {
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val session = (identity as AppResult.Success).value ?: return AppResult.Success(null)
        val selected = workspaces.current(session.id)
        currentCoroutineContext().ensureActive()
        if (selected is AppResult.Failed) return selected
        val workspace = (selected as AppResult.Success).value ?: return AppResult.Success(null)
        val captured = sessions.captureMessageAccess(workspace.id, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (
            access.sessionId != session.id ||
                access.accountId != session.user.id ||
                access.companyId != workspace.company.id
        )
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        return read(CollaborationLocalScope.Selected(workspace.id), access)
    }

    suspend operator fun invoke(
        scope: CollaborationLocalScope
    ): AppResult<CollaborationDraftRecoveryWork?> {
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        return read(scope, (captured as AppResult.Success).value)
    }

    private suspend fun read(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<CollaborationDraftRecoveryWork?> {
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        val position = cursors.current(access)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (position is AppResult.Failed) return position
        val cursor = (position as AppResult.Success).value
        if (
            cursor != null &&
                (!validCollaborationMessageId(cursor.generation) ||
                    !validCollaborationMessageId(cursor.afterConversationId))
        )
            return changedDraft()
        var after = cursor?.afterConversationId
        var page = drafts.prepared(access, after)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (page is AppResult.Failed) return page
        if ((page as AppResult.Success).value.drafts.isEmpty() && cursor != null) {
            // At most one wrap. Held drafts are excluded by the same atomic document index.
            after = null
            page = drafts.prepared(access)
            guard(scope, access).let { if (it is AppResult.Failed) return it }
            if (page is AppResult.Failed) return page
        }
        val value = (page as AppResult.Success).value
        val pageAfter = after
        if (
            value.drafts.size > 8 ||
                value.drafts.any {
                    !validCollaborationDraft(it) ||
                        it.submissionId == null ||
                        it.reviewRequired ||
                        (pageAfter != null && it.conversationId <= pageAfter)
                } ||
                value.drafts.map { it.conversationId } !=
                    value.drafts.map { it.conversationId }.distinct().sorted() ||
                (value.next != null &&
                    (value.drafts.size != 8 || value.next != value.drafts.last().conversationId))
        )
            return changedDraft()
        return AppResult.Success(
            if (value.drafts.isEmpty()) null
            else
                CollaborationDraftRecoveryWork(
                    scope,
                    access,
                    cursor,
                    value.drafts,
                    value.next != null,
                )
        )
    }

    private suspend fun guard(scope: CollaborationLocalScope, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(scope, access, lists, workspaces, server, build, clock)
}
