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
import kotlinx.coroutines.sync.Mutex

/** Reproduces the durable message before removing an exact retained input version. */
class CompleteCollaborationNotificationReply(
    private val inputs: CollaborationNotificationReplyRepository,
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        expected: CollaborationNotificationReplyInput
    ): AppResult<CollaborationNotificationReplyCompletion> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReplyInput(expected))
            return completionFailure(FailureKind.Validation)
        if (!execution.tryLock()) return completionFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(10_000) { complete(expected) }
                ?: completionFailure(FailureKind.Timeout)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun complete(
        expected: CollaborationNotificationReplyInput
    ): AppResult<CollaborationNotificationReplyCompletion> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val candidate = expected.candidate
        if (
            candidate.parent.audience != parent.audience ||
                candidate.parent.accountId != parent.accountId
        )
            return completionFailure(FailureKind.Unauthorized)
        // Same-account reauthentication can recognize an existing command without using the old
        // token as a new send grant. All pending operations remain pinned to the current family.
        val scope =
            CollaborationLocalScope.Captured(
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    candidate.action.target.companyId,
                ),
                parent.nativeSessionId,
            )
        val recovery =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        guard(scope).let { if (it is AppResult.Failed) return it }
        val found = inputs.get(recovery, expected.id)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val retained =
            (found as AppResult.Success).value
                ?: return AppResult.Success(CollaborationNotificationReplyCompletion.Absent)
        if (retained != expected) return completionFailure(FailureKind.Conflict)
        // A working edit is independent of original delivery. Only explicit send/discard may
        // retire it; a duplicate native callback must not silently erase even an empty draft.
        if (retained.draft != null)
            return AppResult.Success(CollaborationNotificationReplyCompletion.Pending)
        val action = retained.candidate.action
        val operationId = retained.resolution?.operationId ?: action.operationId
        val text = retained.resolution?.text ?: retained.candidate.text
        // Original message history is not required to prove an already retained reply command.
        val read = messages.get(scope.access, operationId)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val message = (read as AppResult.Success).value
        // An exact cancelled operation grants no delivery or read. Its permanent owned archive
        // must also suppress a duplicate callback when disposable conversation metadata is absent.
        if (message?.state != CollaborationMessageState.Cancelled) {
            val conversation = lists.get(scope.access, action.target.conversationId)
            guard(scope).let { if (it is AppResult.Failed) return it }
            if (conversation is AppResult.Failed) return conversation
            if (
                !ownsCollaborationReadConversation(
                    scope.access,
                    action.target.conversationId,
                    (conversation as AppResult.Success).value,
                )
            )
                return completionFailure(FailureKind.Unavailable)
        }
        if (message == null)
            return AppResult.Success(CollaborationNotificationReplyCompletion.Pending)
        if (message.state == CollaborationMessageState.Cancelled && message.revision != 0L)
            return completionFailure(FailureKind.Conflict)
        if (
            message.intent.id != operationId ||
                message.intent.owner != scope.access.messageOwner() ||
                message.intent.conversationId != action.target.conversationId ||
                message.intent.text != text ||
                message.revision < 0 ||
                !validCollaborationMessageState(message.state)
        )
            return completionFailure(FailureKind.Conflict)
        currentCoroutineContext().ensureActive()
        val removed = inputs.remove(recovery, retained)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (removed is AppResult.Failed) return removed
        return if ((removed as AppResult.Success).value)
            AppResult.Success(CollaborationNotificationReplyCompletion.Retired(message.state))
        else completionFailure(FailureKind.Conflict)
    }

    private suspend fun guard(scope: CollaborationLocalScope.Captured) =
        sessions.checkReadStateContinuation(
            scope,
            scope.access,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}

private fun completionFailure(kind: FailureKind) =
    collaborationSetupFailure(
        "client.collaboration_notification_reply_completion_unavailable",
        kind,
    )
