package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.isPendingPublication
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import kotlinx.coroutines.*

/** Authenticated native actions; Reply input has its separate durable capture owner. */
class CollaborationNotificationActions(
    private val owner: CollaborationNotificationSurfaceOwner,
    private val open: OpenCollaborationNotificationAction,
    private val commands: CollaborationNotificationCommands,
    private val read: ReadCollaborationNotificationPublications,
    private val cancel: CancelCollaborationNotificationPublication,
) {
    suspend operator fun invoke(
        input: ActionNotificationInput
    ): AppResult<CollaborationNotificationActionOutcome> {
        currentCoroutineContext().ensureActive()
        if (input.kind == ActionNotificationKind.Reply || input.replyText != null)
            return surfaceFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { apply(input) } ?: surfaceFailure(FailureKind.Timeout)
    }

    private suspend fun apply(
        input: ActionNotificationInput
    ): AppResult<CollaborationNotificationActionOutcome> {
        val token = CollaborationNotificationActionToken(input.envelope)
        val opened = open(token)
        if (opened is AppResult.Failed) return opened
        val invocation = (opened as AppResult.Success).value
        val action = invocation.action
        val target = action.target
        val matchesKind =
            when (input.kind) {
                ActionNotificationKind.Open -> action is CollaborationNotificationAction.Reply
                ActionNotificationKind.MarkRead ->
                    action is CollaborationNotificationAction.MarkRead
                ActionNotificationKind.Mute -> action is CollaborationNotificationAction.Mute
                ActionNotificationKind.Dismiss -> action is CollaborationNotificationAction.Dismiss
                ActionNotificationKind.Reply -> false
            }
        if (
            !matchesKind ||
                input.reference !=
                    ActionNotificationReference(target.notificationId, target.renderId)
        )
            return surfaceFailure(FailureKind.Validation)
        // A tap is navigation only. Route admission and actual visible-message coverage decide
        // later reads. An older render may still open its currently admitted conversation.
        if (input.kind == ActionNotificationKind.Open)
            return AppResult.Success(
                CollaborationNotificationActionOutcome.Navigate(
                    invocation.scope,
                    target.conversationId,
                )
            )
        val access = invocation.scope.access
        val parent =
            CollaborationNativeParent(
                access.sessionId,
                access.audience,
                access.accountId,
                invocation.scope.nativeSessionId ?: return surfaceFailure(FailureKind.Validation),
            )
        return owner.withOwner(parent) {
            val found = read(parent, target)
            if (found is AppResult.Failed) return@withOwner found
            val original = (found as AppResult.Success).value
            if (
                original == null ||
                    !original.state.isPendingPublication() ||
                    (original.state == CollaborationNotificationPublicationState.Dismissing &&
                        input.kind != ActionNotificationKind.Dismiss)
            )
                return@withOwner AppResult.Success(CollaborationNotificationActionOutcome.Obsolete)
            var reference = original
            val applied = commands(token)
            if (applied is AppResult.Failed) return@withOwner applied
            if ((applied as AppResult.Success).value.invocation != invocation)
                return@withOwner surfaceFailure(FailureKind.Conflict)
            if (input.kind == ActionNotificationKind.Dismiss) {
                val requested = cancel.requestDismissal(parent, reference)
                if (requested is AppResult.Failed) return@withOwner requested
                when (val saved = (requested as AppResult.Success).value) {
                    CollaborationNotificationPublicationSettlement.Contended ->
                        return@withOwner surfaceFailure(FailureKind.Conflict)
                    is CollaborationNotificationPublicationSettlement.Settled -> {
                        if (
                            saved.id != reference.id ||
                                saved.notificationId != input.reference.id ||
                                saved.renderId != input.reference.renderId ||
                                saved.state != CollaborationNotificationPublicationState.Dismissing
                        )
                            return@withOwner surfaceFailure(FailureKind.Conflict)
                        reference = reference.copy(version = saved.version, state = saved.state)
                    }
                }
            }
            // Local read/mute may already be retained if cleanup loses its response. Neither a
            // retry nor this completion means that a network receipt was delivered.
            val removed = remove(input.reference)
            if (removed is AppResult.Failed) return@withOwner removed
            if ((removed as AppResult.Success).value == ActionNotificationRemoval.Replaced)
                return@withOwner surfaceFailure(FailureKind.Conflict)
            val settled = cancel(parent, reference)
            if (settled is AppResult.Failed) return@withOwner settled
            when ((settled as AppResult.Success).value) {
                CollaborationNotificationPublicationSettlement.Contended ->
                    surfaceFailure(FailureKind.Conflict)
                is CollaborationNotificationPublicationSettlement.Settled ->
                    AppResult.Success(CollaborationNotificationActionOutcome.Completed(input.kind))
            }
        }
    }
}

sealed interface CollaborationNotificationActionOutcome {
    data class Navigate(val scope: CollaborationLocalScope.Captured, val conversationId: String) :
        CollaborationNotificationActionOutcome {
        override fun toString() = "NotificationNavigation([redacted])"
    }

    data class Completed(val kind: ActionNotificationKind) : CollaborationNotificationActionOutcome

    data object Obsolete : CollaborationNotificationActionOutcome
}
