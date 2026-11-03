package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Finite admitted publication inside a native owner. The host separately owns candidate discovery,
 * ongoing company/source invalidation, foreground suppression and authenticated native callbacks.
 */
class CollaborationNotificationPublisher(
    private val owner: CollaborationNotificationSurfaceOwner,
    private val prepare: PrepareCollaborationMessageNotification,
    private val issue: IssueCollaborationMessageNotification,
    private val validate: ValidateCollaborationNotificationPublication,
    private val finish: FinishCollaborationNotificationPublication,
) {
    suspend fun publish(
        parent: CollaborationNativeParent,
        candidate: CollaborationNotificationCandidate,
        copy: CollaborationNotificationCopy,
        allowAlert: Boolean = false,
    ): AppResult<CollaborationNotificationPublicationResult> =
        owner.withOwner(parent) {
            val access =
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    candidate.companyId,
                )
            val scope = CollaborationLocalScope.Captured(access, parent.nativeSessionId)
            val projected =
                prepare(scope, candidate.conversationId, candidate.roomId, candidate.eventId)
            if (projected is AppResult.Failed) return@withOwner projected
            val message =
                when (val projection = (projected as AppResult.Success).value) {
                    CollaborationNotificationProjection.PendingHistory ->
                        return@withOwner AppResult.Success(
                            CollaborationNotificationPublicationResult.PendingHistory
                        )
                    is CollaborationNotificationProjection.Suppressed ->
                        return@withOwner AppResult.Success(
                            CollaborationNotificationPublicationResult.Suppressed
                        )
                    is CollaborationNotificationProjection.Ready -> projection.message
                }
            val issued = issue(scope, message)
            if (issued is AppResult.Failed) return@withOwner issued
            val ready =
                when (val value = (issued as AppResult.Success).value) {
                    CollaborationNotificationIssuance.Unchanged ->
                        return@withOwner AppResult.Success(
                            CollaborationNotificationPublicationResult.Unchanged
                        )
                    is CollaborationNotificationIssuance.Ready -> value
                }
            submit(ready, copy, allowAlert)
        }

    private suspend fun CollaborationNotificationSurfaceLease.submit(
        ready: CollaborationNotificationIssuance.Ready,
        copy: CollaborationNotificationCopy,
        allowAlert: Boolean,
    ): AppResult<CollaborationNotificationPublicationResult> {
        val publication = ready.publication
        validate(publication).let { if (it is AppResult.Failed) return it }
        val payload = publication.nativePayload(copy, silent = !allowAlert || !ready.newOriginal)
        val submitted = show(payload)
        if (submitted is AppResult.Failed) return submitted
        when ((submitted as AppResult.Success).value) {
            ActionNotificationSubmission.PermissionRequired ->
                return AppResult.Success(
                    CollaborationNotificationPublicationResult.PermissionRequired
                )
            ActionNotificationSubmission.ChannelDisabled ->
                return AppResult.Success(CollaborationNotificationPublicationResult.ChannelDisabled)
            ActionNotificationSubmission.CapacityReached ->
                return AppResult.Success(CollaborationNotificationPublicationResult.CapacityReached)
            ActionNotificationSubmission.Submitted -> Unit
        }
        // External submission can complete while source admission changes. Failure triggers the
        // owner's exact-reference compensation, while the journal remains recoverable.
        validate(publication).let { if (it is AppResult.Failed) return it }
        val result = finish(publication, CollaborationNotificationPublicationState.Published)
        if (result is AppResult.Failed) return result
        val receipt =
            when (val value = (result as AppResult.Success).value) {
                CollaborationNotificationPublicationSettlement.Contended ->
                    return surfaceFailure(FailureKind.Conflict)
                is CollaborationNotificationPublicationSettlement.Settled -> value
            }
        if (
            receipt.id != publication.id ||
                receipt.notificationId != payload.reference.id ||
                receipt.renderId != payload.reference.renderId ||
                receipt.state != CollaborationNotificationPublicationState.Published
        )
            return surfaceFailure(FailureKind.Conflict)
        val acknowledged =
            publication.copy(
                version = receipt.version,
                body =
                    publication.body.copy(
                        state = CollaborationNotificationPublicationState.Published
                    ),
            )
        validate(acknowledged).let { if (it is AppResult.Failed) return it }
        currentCoroutineContext().ensureActive()
        return AppResult.Success(CollaborationNotificationPublicationResult.Submitted)
    }
}
