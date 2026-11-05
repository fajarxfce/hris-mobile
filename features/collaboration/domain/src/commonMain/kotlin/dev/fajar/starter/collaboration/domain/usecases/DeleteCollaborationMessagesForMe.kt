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

/** Owned local deletion only: no credentials, SDK mutation, send cancellation or network. */
class DeleteCollaborationMessagesForMe(
    private val visibility: CollaborationLocalVisibilityRepository,
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
        conversationId: String,
        roomId: String,
        messages: List<CollaborationMessageReference>,
    ): AppResult<Unit> {
        if (
            !validCollaborationVisibilitySelection(
                conversationId,
                roomId,
                messages,
                COLLABORATION_LOCAL_DELETE_LIMIT,
                false,
            )
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_message",
                FailureKind.Validation,
            )
        val selected = messages.toList()
        return withTimeoutOrNull(15_000) {
            guard(workspaceId, expectedAccess).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val retained = lists.get(expectedAccess, conversationId)
            guard(workspaceId, expectedAccess).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (retained is AppResult.Failed) return@withTimeoutOrNull retained
            if (
                !ownsCollaborationReadConversation(
                    expectedAccess,
                    conversationId,
                    (retained as AppResult.Success).value,
                )
            )
                return@withTimeoutOrNull collaborationSetupFailure(
                    "client.collaboration_conversation_unavailable",
                    FailureKind.Unavailable,
                )
            repeat(2) {
                currentCoroutineContext().ensureActive()
                val saved = visibility.hide(expectedAccess, conversationId, roomId, selected)
                guard(workspaceId, expectedAccess).let {
                    if (it is AppResult.Failed) return@withTimeoutOrNull it
                }
                if (saved is AppResult.Failed) return@withTimeoutOrNull saved
                if ((saved as AppResult.Success).value)
                    return@withTimeoutOrNull AppResult.Success(Unit)
            }
            collaborationSetupFailure(
                "client.collaboration_local_delete_pending",
                FailureKind.Conflict,
            )
        }
            ?: collaborationSetupFailure(
                "client.collaboration_local_delete_pending",
                FailureKind.Unavailable,
            )
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
}
