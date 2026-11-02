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

/** Authenticates native callback metadata against the current session and acquired local facts. */
class OpenCollaborationNotificationAction(
    private val actions: CollaborationNotificationActionRepository,
    private val lists: CollaborationListRepository,
    private val history: CollaborationHistoryRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        token: CollaborationNotificationActionToken
    ): AppResult<CollaborationNotificationInvocation> {
        currentCoroutineContext().ensureActive()
        if (token.encoded.length !in 4..4096 || !token.encoded.startsWith("v1."))
            return notificationActionFailure(FailureKind.Validation)
        return withTimeoutOrNull(10_000) { open(token) }
            ?: notificationActionFailure(FailureKind.Unavailable)
    }

    private suspend fun open(
        token: CollaborationNotificationActionToken
    ): AppResult<CollaborationNotificationInvocation> {
        // The owner is never supplied by a native Intent or selected workspace.
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val decoded = actions.open(parent, token)
        currentCoroutineContext().ensureActive()
        if (decoded is AppResult.Failed) {
            val current = sessions.guardPushParent(server, build, parent)
            return if (current is AppResult.Failed) current else decoded
        }
        val action = (decoded as AppResult.Success).value
        if (!validCollaborationNotificationAction(action))
            return notificationActionFailure(FailureKind.Validation)
        val scope =
            CollaborationLocalScope.Captured(
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    action.target.companyId,
                ),
                parent.nativeSessionId,
            )
        continuation(scope).let { if (it is AppResult.Failed) return it }
        val found = lists.get(scope.access, action.target.conversationId)
        continuation(scope).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        if (
            !ownsCollaborationReadConversation(
                scope.access,
                action.target.conversationId,
                (found as AppResult.Success).value,
            )
        )
            return notificationActionFailure(FailureKind.Unavailable)
        // Mute and dismiss act on the acquired conversation/card, even after history removal.
        // Reply/read must refer to an acquired exact event; a retained tombstone is still a fact.
        if (
            action is CollaborationNotificationAction.Reply ||
                action is CollaborationNotificationAction.MarkRead
        ) {
            val fact =
                history.getEventFact(
                    scope.access,
                    action.target.conversationId,
                    action.target.roomId,
                    action.target.eventId,
                )
            continuation(scope).let { if (it is AppResult.Failed) return it }
            if (fact is AppResult.Failed) return fact
            if (
                !validCollaborationReadFact(
                    (fact as AppResult.Success).value,
                    action.target.eventId,
                )
            )
                return notificationActionFailure(FailureKind.Unavailable)
        }
        return AppResult.Success(CollaborationNotificationInvocation(scope, action))
    }

    private suspend fun continuation(scope: CollaborationLocalScope.Captured) =
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

private fun notificationActionFailure(kind: FailureKind) =
    collaborationSetupFailure("client.collaboration_notification_action_unavailable", kind)
