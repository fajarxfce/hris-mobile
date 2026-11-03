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

    /** Called only after protected reply capture succeeds. Never queues a send or marks read. */
    suspend fun acknowledgeReply(token: CollaborationNotificationActionToken): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(8_000) {
            val opened = open(token)
            if (opened is AppResult.Failed) return@withTimeoutOrNull opened
            val invocation = (opened as AppResult.Success).value
            val action = invocation.action
            if (action !is CollaborationNotificationAction.Reply)
                return@withTimeoutOrNull surfaceFailure(FailureKind.Validation)
            val access = invocation.scope.access
            val native =
                invocation.scope.nativeSessionId
                    ?: return@withTimeoutOrNull surfaceFailure(FailureKind.Validation)
            val parent =
                CollaborationNativeParent(
                    access.sessionId,
                    access.audience,
                    access.accountId,
                    native,
                )
            owner.withOwner(parent) {
                val found = read(parent, action.target)
                if (found is AppResult.Failed) return@withOwner found
                val reference = (found as AppResult.Success).value
                if (reference == null || !reference.state.isPendingPublication())
                    return@withOwner AppResult.Success(Unit)
                val requested = beginDismissal(parent, reference)
                if (requested is AppResult.Failed) return@withOwner requested
                removeAndFinish(parent, (requested as AppResult.Success).value)
            }
        } ?: surfaceFailure(FailureKind.Timeout)
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
                val requested = beginDismissal(parent, reference)
                if (requested is AppResult.Failed) return@withOwner requested
                reference = (requested as AppResult.Success).value
            }
            val finished = removeAndFinish(parent, reference)
            if (finished is AppResult.Failed) return@withOwner finished
            AppResult.Success(CollaborationNotificationActionOutcome.Completed(input.kind))
        }
    }

    private suspend fun beginDismissal(
        parent: CollaborationNativeParent,
        reference: CollaborationNotificationPublicationReference,
    ): AppResult<CollaborationNotificationPublicationReference> {
        val requested = cancel.requestDismissal(parent, reference)
        if (requested is AppResult.Failed) return requested
        return when (val saved = (requested as AppResult.Success).value) {
            CollaborationNotificationPublicationSettlement.Contended ->
                surfaceFailure(FailureKind.Conflict)
            is CollaborationNotificationPublicationSettlement.Settled -> {
                if (
                    saved.id != reference.id ||
                        saved.notificationId != reference.target.notificationId ||
                        saved.renderId != reference.target.renderId ||
                        saved.state != CollaborationNotificationPublicationState.Dismissing
                )
                    surfaceFailure(FailureKind.Conflict)
                else AppResult.Success(reference.copy(version = saved.version, state = saved.state))
            }
        }
    }

    private suspend fun CollaborationNotificationSurfaceLease.removeAndFinish(
        parent: CollaborationNativeParent,
        reference: CollaborationNotificationPublicationReference,
    ): AppResult<Unit> {
        val removed =
            remove(
                ActionNotificationReference(
                    reference.target.notificationId,
                    reference.target.renderId,
                )
            )
        if (removed is AppResult.Failed) return removed
        if ((removed as AppResult.Success).value == ActionNotificationRemoval.Replaced)
            return surfaceFailure(FailureKind.Conflict)
        val settled = cancel(parent, reference)
        if (settled is AppResult.Failed) return settled
        return when ((settled as AppResult.Success).value) {
            CollaborationNotificationPublicationSettlement.Contended ->
                surfaceFailure(FailureKind.Conflict)
            is CollaborationNotificationPublicationSettlement.Settled -> AppResult.Success(Unit)
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
