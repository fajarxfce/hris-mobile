@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Clear only after the exact command is durable, including an authenticated archived completion.
 */
class ConfirmCollaborationDraftSend(
    private val drafts: CollaborationDraftRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val messages: CollaborationMessageRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationDraft,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationDraft?> =
        invoke(CollaborationLocalScope.Selected(workspaceId), expected, expectedAccess)

    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        expected: CollaborationDraft,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationDraft?> {
        if (!validCollaborationDraft(expected) || expected.submissionId == null)
            return invalidDraft()
        val conversationId = expected.conversationId
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (expectedAccess != null && access != expectedAccess)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        val command = messages.get(access, expected.submissionId)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (command is AppResult.Failed) return command
        val intent = (command as AppResult.Success).value?.intent
        if (
            intent?.id != expected.submissionId ||
                intent.owner != access.messageOwner() ||
                intent.conversationId != conversationId ||
                intent.text != expected.text
        )
            return collaborationSetupFailure(
                "client.collaboration_message_changed",
                FailureKind.Conflict,
            )
        val read = drafts.get(access, conversationId)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val current = (read as AppResult.Success).value
        // A previous acknowledgement or a concurrent editor owns the new slot; preserve it.
        if (current?.submissionId != expected.submissionId || current.text != expected.text)
            return AppResult.Success(current)
        val replacement =
            current.copy(
                revision = Uuid.random().toString(),
                text = "",
                savedAt = clock.now(),
                submissionId = null,
                reviewRequired = false,
            )
        currentCoroutineContext().ensureActive()
        val written = drafts.replace(access, current, replacement)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        return if ((written as AppResult.Success).value) AppResult.Success(replacement)
        else changedDraft()
    }

    private suspend fun guard(scope: CollaborationLocalScope, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(scope, access, lists, workspaces, server, build, clock)
}
