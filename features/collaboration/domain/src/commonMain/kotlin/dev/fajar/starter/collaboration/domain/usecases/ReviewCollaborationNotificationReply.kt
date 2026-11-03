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

/** Holds private input only from reproduced facts; never clears it, changes text or queues work. */
class ReviewCollaborationNotificationReply(
    private val inputs: CollaborationNotificationReplyRepository,
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val history: CollaborationHistoryRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    /** Null means no hold is currently justified; it is not a send or completion grant. */
    suspend operator fun invoke(
        expected: CollaborationNotificationReplyInput
    ): AppResult<CollaborationNotificationReplyInput?> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReplyInput(expected))
            return unavailable(FailureKind.Validation)
        if (!execution.tryLock()) return unavailable(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(10_000) { review(expected) } ?: unavailable(FailureKind.Timeout)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun review(
        expected: CollaborationNotificationReplyInput
    ): AppResult<CollaborationNotificationReplyInput?> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val candidate = expected.candidate
        if (
            candidate.parent.audience != parent.audience ||
                candidate.parent.accountId != parent.accountId
        )
            return unavailable(FailureKind.Unauthorized)
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
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        guard(scope).let { if (it is AppResult.Failed) return it }
        val found = inputs.get(access, expected.id)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val input = (found as AppResult.Success).value ?: return AppResult.Success(null)
        if (input != expected) return unavailable(FailureKind.Conflict)
        if (input.review != null) return AppResult.Success(input)
        val operationId = input.resolution?.operationId ?: candidate.action.operationId
        val text = input.resolution?.text ?: candidate.text
        val approvedParent = input.resolution?.parent ?: candidate.parent
        val read = messages.get(scope.access, operationId)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val message = (read as AppResult.Success).value
        val reason =
            if (message != null) {
                if (
                    message.intent.id != operationId ||
                        message.intent.owner != scope.access.messageOwner() ||
                        !validCollaborationMessageId(message.intent.conversationId) ||
                        !validCollaborationText(message.intent.text) ||
                        message.revision < 0 ||
                        !validCollaborationMessageState(message.state)
                )
                    return unavailable(FailureKind.Conflict)
                if (
                    message.intent.conversationId == candidate.action.target.conversationId &&
                        message.intent.text == text
                )
                    return AppResult.Success(null)
                CollaborationNotificationReplyReview.MessageChanged
            } else if (approvedParent != parent) CollaborationNotificationReplyReview.SessionChanged
            else {
                val context = lists.get(scope.access, candidate.action.target.conversationId)
                guard(scope).let { if (it is AppResult.Failed) return it }
                if (context is AppResult.Failed) return context
                if (
                    ownsCollaborationReadConversation(
                        scope.access,
                        candidate.action.target.conversationId,
                        (context as AppResult.Success).value,
                    )
                ) {
                    if (input.resolution != null) return AppResult.Success(null)
                    val fact =
                        history.getEventFact(
                            scope.access,
                            candidate.action.target.conversationId,
                            candidate.action.target.roomId,
                            candidate.action.target.eventId,
                        )
                    guard(scope).let { if (it is AppResult.Failed) return it }
                    if (fact is AppResult.Failed) return fact
                    val original = (fact as AppResult.Success).value
                    if (original != null) {
                        if (!validCollaborationReadFact(original, candidate.action.target.eventId))
                            return unavailable(FailureKind.Conflict)
                        return AppResult.Success(null)
                    }
                }
                CollaborationNotificationReplyReview.ContextUnavailable
            }
        return hold(scope, access, input, reason)
    }

    private suspend fun hold(
        scope: CollaborationLocalScope.Captured,
        access: CollaborationRecoveryAccess,
        input: CollaborationNotificationReplyInput,
        reason: CollaborationNotificationReplyReview,
    ): AppResult<CollaborationNotificationReplyInput?> {
        currentCoroutineContext().ensureActive()
        val held = inputs.hold(access, input, reason)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (held is AppResult.Failed) return held
        if (!(held as AppResult.Success).value) return unavailable(FailureKind.Conflict)
        val reread = inputs.get(access, input.id)
        guard(scope).let { if (it is AppResult.Failed) return it }
        if (reread is AppResult.Failed) return reread
        val current =
            (reread as AppResult.Success).value ?: return unavailable(FailureKind.Conflict)
        if (
            !validCollaborationNotificationReplyInput(current) ||
                current.id != input.id ||
                current.candidate != input.candidate ||
                current.sequence != input.sequence ||
                current.version.generation != input.version.generation ||
                current.version.revision <= input.version.revision ||
                current.review != reason
        )
            return unavailable(FailureKind.Conflict)
        return AppResult.Success(current)
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

    private fun unavailable(kind: FailureKind) =
        collaborationSetupFailure(
            "client.collaboration_notification_reply_review_unavailable",
            kind,
        )
}
