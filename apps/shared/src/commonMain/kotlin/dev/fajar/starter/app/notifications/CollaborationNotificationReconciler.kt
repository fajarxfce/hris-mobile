package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.CancelCollaborationNotificationPublication
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Finite exact-source reconciliation. The process host separately owns observation and retries. */
class CollaborationNotificationReconciler(
    private val owner: CollaborationNotificationSurfaceOwner,
    private val publisher: CollaborationNotificationPublisher,
    private val cancel: CancelCollaborationNotificationPublication,
) {
    suspend fun reconcile(
        parent: CollaborationNativeParent,
        reference: CollaborationNotificationPublicationReference,
        copy: CollaborationNotificationCopy,
        sourceFailure: Failure? = null,
    ): AppResult<CollaborationNotificationReconciliationResult> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationReference(reference) ||
                !reference.state.isActivePublication() ||
                reference.parent.accountId != parent.accountId ||
                reference.parent.audience != parent.audience
        )
            return surfaceFailure(FailureKind.Validation)
        val target = reference.target
        val result =
            sourceFailure?.let { AppResult.Failed(it) }
                ?: publisher.publish(
                    parent,
                    CollaborationNotificationCandidate(
                        target.companyId,
                        target.conversationId,
                        target.roomId,
                        target.eventId,
                    ),
                    copy,
                    allowAlert = false,
                    expected = reference,
                )
        if (result == AppResult.Success(CollaborationNotificationPublicationResult.Submitted))
            return AppResult.Success(CollaborationNotificationReconciliationResult.Submitted)
        if (result == AppResult.Success(CollaborationNotificationPublicationResult.Unchanged))
            return AppResult.Success(CollaborationNotificationReconciliationResult.Contended)
        val retire =
            when (result) {
                is AppResult.Failed -> collaborationListResponseClosesRead(result.failure)
                is AppResult.Success ->
                    result.value == CollaborationNotificationPublicationResult.Suppressed
            }
        return owner.withOwner(parent) {
            // Remove/register the native obligation first. Cancellation during journal I/O must
            // still join exact native compensation; the two services are not one transaction.
            val removed =
                remove(ActionNotificationReference(target.notificationId, target.renderId))
            if (removed is AppResult.Failed) return@withOwner removed
            if ((removed as AppResult.Success).value == ActionNotificationRemoval.Replaced)
                return@withOwner AppResult.Success(
                    CollaborationNotificationReconciliationResult.Contended
                )
            if (!retire)
                return@withOwner AppResult.Success(
                    CollaborationNotificationReconciliationResult.Hidden
                )
            val retired = cancel(parent, reference)
            if (retired is AppResult.Failed) return@withOwner retired
            when ((retired as AppResult.Success).value) {
                CollaborationNotificationPublicationSettlement.Contended ->
                    AppResult.Success(CollaborationNotificationReconciliationResult.Contended)
                is CollaborationNotificationPublicationSettlement.Settled ->
                    AppResult.Success(CollaborationNotificationReconciliationResult.Retired)
            }
        }
    }
}

enum class CollaborationNotificationReconciliationResult {
    Submitted,
    Retired,
    Hidden,
    Contended,
}
