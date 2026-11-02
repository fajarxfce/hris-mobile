package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationText
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/**
 * Application-owned dispatch through the existing durable commands. The native caller must retain
 * reply input before invoking this path and keep it on failure or uncertainty; this is not a
 * failed-input store, token receipt, native receiver or OS publication owner.
 */
class CollaborationNotificationCommands(
    private val open: OpenCollaborationNotificationAction,
    private val queue: QueueCollaborationText,
    private val mark: MarkCollaborationMessagesRead,
    private val settings: UpdateCollaborationNotificationSettings,
    private val recover: RequestCollaborationRecovery,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        token: CollaborationNotificationActionToken,
        replyText: String? = null,
    ): AppResult<CollaborationNotificationCommandResult> {
        currentCoroutineContext().ensureActive()
        if (replyText != null && !validCollaborationText(replyText))
            return failure(FailureKind.Validation)
        if (!execution.tryLock()) return failure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) { dispatch(token, replyText) } ?: failure(FailureKind.Timeout)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun dispatch(
        token: CollaborationNotificationActionToken,
        replyText: String?,
    ): AppResult<CollaborationNotificationCommandResult> {
        val opened = open(token)
        if (opened is AppResult.Failed) return opened
        val invocation = (opened as AppResult.Success).value
        // Only authenticated decoded metadata chooses the operation, never an outer Intent action.
        if ((invocation.action is CollaborationNotificationAction.Reply) != (replyText != null))
            return failure(FailureKind.Validation)
        return when (val action = invocation.action) {
            is CollaborationNotificationAction.Reply -> reply(invocation, requireNotNull(replyText))
            is CollaborationNotificationAction.MarkRead -> read(invocation)
            is CollaborationNotificationAction.Mute -> mute(invocation, action)
            is CollaborationNotificationAction.Dismiss ->
                AppResult.Success(
                    CollaborationNotificationCommandResult.DismissRequested(invocation)
                )
        }
    }

    private suspend fun reply(
        invocation: CollaborationNotificationInvocation,
        text: String,
    ): AppResult<CollaborationNotificationCommandResult> {
        val action = invocation.action
        val stored =
            queue(
                invocation.scope,
                action.target.conversationId,
                action.operationId,
                text,
                invocation.scope.access,
            )
        currentCoroutineContext().ensureActive()
        return when (stored) {
            is AppResult.Failed -> stored
            is AppResult.Success ->
                AppResult.Success(
                    CollaborationNotificationCommandResult.ReplyRetained(
                        invocation,
                        stored.value.state,
                    )
                )
        }
    }

    private suspend fun read(
        invocation: CollaborationNotificationInvocation
    ): AppResult<CollaborationNotificationCommandResult> {
        val action = invocation.action
        val target = action.target
        val stored =
            mark(
                invocation.scope,
                target.conversationId,
                target.roomId,
                listOf(target.eventId),
                CollaborationReadReceiptIntent(
                    action.operationId,
                    target.eventId,
                    CollaborationReadReceiptVisibility.Private,
                ),
                invocation.scope.access,
                onlyIfUnread = true,
            )
        currentCoroutineContext().ensureActive()
        if (stored is AppResult.Failed) return stored
        if ((stored as AppResult.Success).value.pending.isNotEmpty()) {
            // Startup/periodic recovery still discovers the retained intent if scheduling fails.
            withTimeoutOrNull(1_500) { recover() }
            currentCoroutineContext().ensureActive()
        }
        return AppResult.Success(CollaborationNotificationCommandResult.ReadRetained(invocation))
    }

    private suspend fun mute(
        invocation: CollaborationNotificationInvocation,
        action: CollaborationNotificationAction.Mute,
    ): AppResult<CollaborationNotificationCommandResult> {
        val stored =
            settings(
                invocation.scope,
                action.target.conversationId,
                CollaborationNotificationSettingsChange(
                    action.operationId,
                    action.expected,
                    CollaborationNotificationSettings(
                        CollaborationNotificationMute.Forever,
                        action.preview,
                    ),
                ),
                invocation.scope.access,
            )
        currentCoroutineContext().ensureActive()
        return when (stored) {
            is AppResult.Failed -> stored
            is AppResult.Success ->
                AppResult.Success(CollaborationNotificationCommandResult.Muted(invocation))
        }
    }

    private fun failure(kind: FailureKind) =
        AppResult.Failed(
            Failure(
                kind,
                "The chat action could not be completed.",
                code = "client.collaboration_notification_action_unavailable",
            )
        )
}
