package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/**
 * Retains private typed input before company send admission. This never opens company content,
 * changes a composer, grants a send or renews credentials. An authenticated previous family is
 * retained for review; the current family still owns this retention operation.
 */
class RetainCollaborationNotificationReply(
    private val actions: CollaborationNotificationActionRepository,
    private val inputs: CollaborationNotificationReplyRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        token: CollaborationNotificationActionToken,
        text: String,
    ): AppResult<CollaborationNotificationReplyInput> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationTokenShape(token) || !validCollaborationText(text))
            return replyInputFailure(FailureKind.Validation)
        if (!execution.tryLock()) return replyInputFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(10_000) { retain(token, text) }
                ?: replyInputFailure(FailureKind.Unavailable)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun retain(
        token: CollaborationNotificationActionToken,
        text: String,
    ): AppResult<CollaborationNotificationReplyInput> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        return retainAuthenticatedNotificationReply(
            parent,
            token,
            text,
            actions,
            inputs,
            sessions,
            server,
            build,
        )
    }
}

/**
 * Shared repository coordination; callers capture the actual parent and own their operation
 * lifetime.
 */
internal suspend fun retainAuthenticatedNotificationReply(
    parent: CollaborationNativeParent,
    token: CollaborationNotificationActionToken,
    text: String,
    actions: CollaborationNotificationActionRepository,
    inputs: CollaborationNotificationReplyRepository,
    sessions: SessionRepository,
    server: IdentityServer,
    build: AppBuild,
): AppResult<CollaborationNotificationReplyInput> {
    val decoded = actions.recover(parent, token)
    sessions.guardPushParent(server, build, parent).let { if (it is AppResult.Failed) return it }
    if (decoded is AppResult.Failed) return decoded
    val origin = (decoded as AppResult.Success).value
    if (
        !validCollaborationAuthenticatedNotificationAction(origin) ||
            origin.parent.accountId != parent.accountId ||
            origin.parent.audience != parent.audience
    )
        return replyInputFailure(FailureKind.Validation)
    val action =
        origin.action as? CollaborationNotificationAction.Reply
            ?: return replyInputFailure(FailureKind.Validation)
    val candidate = CollaborationNotificationReplyCandidate(origin.parent, action, token, text)
    if (!validCollaborationNotificationReplyCandidate(candidate))
        return replyInputFailure(FailureKind.Validation)
    val saved =
        inputs.retain(
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId),
            candidate,
        )
    sessions.guardPushParent(server, build, parent).let { if (it is AppResult.Failed) return it }
    if (saved is AppResult.Failed) return saved
    return when (val result = (saved as AppResult.Success).value) {
        CollaborationNotificationReplyRetention.CapacityExceeded ->
            collaborationSetupFailure(
                "client.collaboration_notification_reply_capacity",
                FailureKind.Storage,
            )
        CollaborationNotificationReplyRetention.Contended -> replyInputFailure(FailureKind.Conflict)
        is CollaborationNotificationReplyRetention.Retained -> {
            val input = result.input
            if (
                !validCollaborationNotificationReplyInput(input) ||
                    input.candidate.copy(token = token) != candidate
            )
                replyInputFailure(FailureKind.Conflict)
            else AppResult.Success(input)
        }
    }
}

private fun replyInputFailure(kind: FailureKind) =
    collaborationSetupFailure("client.collaboration_notification_reply_unavailable", kind)
