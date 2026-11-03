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

/** Fences the original operation before removing input, including late duplicate OS callbacks. */
class DiscardCollaborationNotificationReply(
    private val inputs: CollaborationNotificationReplyRepository,
    private val messages: CollaborationMessageRepository,
    private val cancellations: CollaborationMessageCancellationRepository,
    private val prevention: CollaborationMessagePreventionRepository,
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
        expected: CollaborationNotificationReplyInput,
    ): AppResult<CollaborationReplyDiscard> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReplyInput(expected))
            return failed(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { discard(workspaceId, expectedAccess, expected) }
            ?: failed(FailureKind.Timeout)
    }

    private suspend fun discard(
        workspaceId: String,
        access: CollaborationLocalAccess,
        expected: CollaborationNotificationReplyInput,
    ): AppResult<CollaborationReplyDiscard> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val candidate = expected.candidate
        if (
            parent.sessionId != access.sessionId ||
                parent.audience != access.audience ||
                parent.accountId != access.accountId ||
                candidate.parent.audience != access.audience ||
                candidate.parent.accountId != access.accountId ||
                candidate.action.target.companyId != access.companyId
        )
            return failed(FailureKind.Unauthorized)
        val scope = CollaborationLocalScope.Selected(workspaceId)
        suspend fun guard(): AppResult<Unit> {
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
        guard().let { if (it is AppResult.Failed) return it }
        val recovery =
            CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)
        val found = inputs.get(recovery, expected.id)
        guard().let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val current =
            (found as AppResult.Success).value ?: return result(CollaborationReplyDiscard.Absent)
        if (current != expected) return failed(FailureKind.Conflict)
        val action = candidate.action
        val read = messages.get(access, action.operationId)
        guard().let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val message = (read as AppResult.Success).value
        if (message != null) {
            if (!matches(message, access, candidate))
                return result(CollaborationReplyDiscard.MessageChanged)
            if (
                message.state != CollaborationMessageState.Queued &&
                    message.state != CollaborationMessageState.Cancelled
            )
                return result(CollaborationReplyDiscard.AlreadyProcessing)
        }
        currentCoroutineContext().ensureActive()
        val fenced =
            when (message?.state) {
                CollaborationMessageState.Cancelled -> AppResult.Success(true)
                CollaborationMessageState.Queued -> cancellations.cancel(access, message)
                else ->
                    prevention.prevent(
                        access,
                        CollaborationMessageIntent(
                            action.operationId,
                            access.messageOwner(),
                            action.target.conversationId,
                            clock.now(),
                            candidate.text,
                        ),
                    )
            }
        guard().let { if (it is AppResult.Failed) return it }
        if (fenced is AppResult.Failed) return fenced
        if (!(fenced as AppResult.Success).value) return failed(FailureKind.Conflict)
        val proof = messages.get(access, action.operationId)
        guard().let { if (it is AppResult.Failed) return it }
        if (proof is AppResult.Failed) return proof
        val terminal = (proof as AppResult.Success).value
        if (
            terminal == null ||
                !matches(terminal, access, candidate) ||
                terminal.state != CollaborationMessageState.Cancelled
        )
            return failed(FailureKind.Conflict)
        currentCoroutineContext().ensureActive()
        val removed = inputs.remove(recovery, expected)
        guard().let { if (it is AppResult.Failed) return it }
        if (removed is AppResult.Failed) return removed
        return if ((removed as AppResult.Success).value) result(CollaborationReplyDiscard.Discarded)
        else failed(FailureKind.Conflict)
    }

    private fun matches(
        message: CollaborationMessageRecord,
        access: CollaborationLocalAccess,
        candidate: CollaborationNotificationReplyCandidate,
    ) =
        message.intent.id == candidate.action.operationId &&
            message.intent.owner == access.messageOwner() &&
            message.intent.conversationId == candidate.action.target.conversationId &&
            message.intent.text == candidate.text &&
            message.revision >= 0 &&
            validCollaborationMessageState(message.state)

    private fun result(value: CollaborationReplyDiscard) = AppResult.Success(value)

    private fun failed(kind: FailureKind) =
        collaborationSetupFailure("client.collaboration_reply_discard_unavailable", kind)
}
