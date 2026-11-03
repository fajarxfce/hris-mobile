package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.OpenCollaborationNotificationAction
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Authenticate ingress independently of native display readiness. */
internal suspend fun resolveNotificationInput(
    input: ActionNotificationInput,
    open: OpenCollaborationNotificationAction,
): AppResult<CollaborationNotificationInvocation> {
    currentCoroutineContext().ensureActive()
    if (input.kind == ActionNotificationKind.Reply || input.replyText != null)
        return surfaceFailure(FailureKind.Validation)
    val opened = open(CollaborationNotificationActionToken(input.envelope))
    currentCoroutineContext().ensureActive()
    if (opened is AppResult.Failed) return opened
    val invocation = (opened as AppResult.Success).value
    val action = invocation.action
    val target = action.target
    val matchesKind =
        when (input.kind) {
            ActionNotificationKind.Open -> action is CollaborationNotificationAction.Reply
            ActionNotificationKind.MarkRead -> action is CollaborationNotificationAction.MarkRead
            ActionNotificationKind.Mute -> action is CollaborationNotificationAction.Mute
            ActionNotificationKind.Dismiss -> action is CollaborationNotificationAction.Dismiss
            ActionNotificationKind.Reply -> false
        }
    if (
        !matchesKind ||
            input.reference != ActionNotificationReference(target.notificationId, target.renderId)
    )
        return surfaceFailure(FailureKind.Validation)
    return AppResult.Success(invocation)
}
