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

/** Local editing; the caller retains operationRevision and text until its write is acknowledged. */
class EditCollaborationDraft(
    private val drafts: CollaborationDraftRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        conversationId: String,
        expected: CollaborationDraft?,
        operationRevision: String,
        text: String,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationDraft> {
        if (
            !validCollaborationMessageId(conversationId) ||
                !validCollaborationMessageId(operationRevision) ||
                !validCollaborationDraftText(text) ||
                (expected != null &&
                    (!validCollaborationDraft(expected) ||
                        expected.conversationId != conversationId ||
                        expected.submissionId != null ||
                        expected.revision == operationRevision))
        )
            return invalidDraft()
        val captured = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (expectedAccess != null && access != expectedAccess)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        val summary = lists.get(access, conversationId)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (summary is AppResult.Failed) return summary
        if (!ownsDraftConversation(access, conversationId, (summary as AppResult.Success).value))
            return collaborationSetupFailure(
                "client.collaboration_conversation_unavailable",
                FailureKind.Unavailable,
            )
        val read = drafts.get(access, conversationId)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val current = (read as AppResult.Success).value
        if (current?.revision == operationRevision)
            return if (current.text == text && current.submissionId == null)
                AppResult.Success(current)
            else changedDraft()
        if (current != expected) return changedDraft()
        val replacement = CollaborationDraft(conversationId, operationRevision, text, clock.now())
        currentCoroutineContext().ensureActive()
        val written = drafts.replace(access, current, replacement)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        return if ((written as AppResult.Success).value) AppResult.Success(replacement)
        else changedDraft()
    }

    private suspend fun guard(workspaceId: String, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(
            workspaceId,
            access,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
