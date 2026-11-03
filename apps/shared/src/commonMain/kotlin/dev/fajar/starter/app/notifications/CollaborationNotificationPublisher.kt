package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.isActivePublication
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
    private val prepareMute: PrepareCollaborationMutedNotification,
    private val commitMute: CommitCollaborationMutedNotification,
    private val focus: CollaborationNotificationFocus = CollaborationNotificationFocus(),
) {
    suspend fun publish(
        parent: CollaborationNativeParent,
        candidate: CollaborationNotificationCandidate,
        copy: CollaborationNotificationCopy,
        allowAlert: Boolean = false,
        expected: CollaborationNotificationPublicationReference? = null,
    ): AppResult<CollaborationNotificationPublicationResult> =
        owner.withOwner(parent) {
            val focused = focus.capture()
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
                    is CollaborationNotificationProjection.Suppressed -> {
                        if (projection.reason == CollaborationNotificationSuppression.Muted)
                            return@withOwner suppressMuted(parent, access, candidate, expected)
                        return@withOwner AppResult.Success(
                            CollaborationNotificationPublicationResult.Suppressed
                        )
                    }
                    is CollaborationNotificationProjection.Ready -> projection.message
                }
            if (!focus.current(focused)) return@withOwner surfaceFailure(FailureKind.Conflict)
            val issued = issue(scope, message, expected)
            if (issued is AppResult.Failed) return@withOwner issued
            val ready =
                when (val value = (issued as AppResult.Success).value) {
                    CollaborationNotificationIssuance.Unchanged ->
                        return@withOwner AppResult.Success(
                            CollaborationNotificationPublicationResult.Unchanged
                        )
                    is CollaborationNotificationIssuance.Ready -> value
                }
            if (!focus.current(focused)) return@withOwner surfaceFailure(FailureKind.Conflict)
            if (focused.matches(parent, candidate)) suppressFocused(ready, focused)
            else submit(ready, copy, allowAlert, focused)
        }

    private suspend fun CollaborationNotificationSurfaceLease.suppressMuted(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
        candidate: CollaborationNotificationCandidate,
        expected: CollaborationNotificationPublicationReference?,
    ): AppResult<CollaborationNotificationPublicationResult> {
        val prepared =
            prepareMute(
                parent,
                access,
                candidate.conversationId,
                candidate.roomId,
                candidate.eventId,
            )
        if (prepared is AppResult.Failed) return prepared
        val plan =
            when (val value = (prepared as AppResult.Success).value) {
                CollaborationMutedNotificationPreparation.PendingHistory ->
                    return AppResult.Success(
                        CollaborationNotificationPublicationResult.PendingHistory
                    )
                CollaborationMutedNotificationPreparation.AlreadySuppressed ->
                    return AppResult.Success(CollaborationNotificationPublicationResult.Suppressed)
                is CollaborationMutedNotificationPreparation.Ready -> value.plan
            }
        if (expected != null && plan.previous != expected)
            return AppResult.Success(CollaborationNotificationPublicationResult.Unchanged)
        val previous = plan.previous
        if (previous != null && previous.state.isActivePublication()) {
            val removed =
                remove(
                    ActionNotificationReference(
                        previous.target.notificationId,
                        previous.target.renderId,
                    )
                )
            if (removed is AppResult.Failed) return removed
            if ((removed as AppResult.Success).value == ActionNotificationRemoval.Replaced)
                return surfaceFailure(FailureKind.Conflict)
        }
        val committed = commitMute(plan)
        if (committed is AppResult.Failed) return committed
        return AppResult.Success(CollaborationNotificationPublicationResult.SuppressionRecorded)
    }

    private suspend fun CollaborationNotificationSurfaceLease.suppressFocused(
        ready: CollaborationNotificationIssuance.Ready,
        focused: CollaborationNotificationFocus.Snapshot,
    ): AppResult<CollaborationNotificationPublicationResult> {
        val publication = ready.publication
        validate(publication).let { if (it is AppResult.Failed) return it }
        if (!focus.current(focused)) return surfaceFailure(FailureKind.Conflict)
        val previous = ready.previous
        if (previous != null && previous.state.isActivePublication()) {
            val removed =
                remove(
                    ActionNotificationReference(
                        previous.target.notificationId,
                        previous.target.renderId,
                    )
                )
            if (removed is AppResult.Failed) return removed
            if ((removed as AppResult.Success).value == ActionNotificationRemoval.Replaced)
                return surfaceFailure(FailureKind.Conflict)
        }
        validate(publication).let { if (it is AppResult.Failed) return it }
        if (!focus.current(focused)) return surfaceFailure(FailureKind.Conflict)
        val finished = finish(publication, CollaborationNotificationPublicationState.Cancelled)
        if (finished is AppResult.Failed) return finished
        val receipt =
            when (val value = (finished as AppResult.Success).value) {
                CollaborationNotificationPublicationSettlement.Contended ->
                    return surfaceFailure(FailureKind.Conflict)
                is CollaborationNotificationPublicationSettlement.Settled -> value
            }
        validate.afterCancellation(publication, receipt).let {
            if (it is AppResult.Failed) return it
        }
        if (!focus.current(focused)) return surfaceFailure(FailureKind.Conflict)
        currentCoroutineContext().ensureActive()
        return AppResult.Success(CollaborationNotificationPublicationResult.SuppressionRecorded)
    }

    private suspend fun CollaborationNotificationSurfaceLease.submit(
        ready: CollaborationNotificationIssuance.Ready,
        copy: CollaborationNotificationCopy,
        allowAlert: Boolean,
        focused: CollaborationNotificationFocus.Snapshot,
    ): AppResult<CollaborationNotificationPublicationResult> {
        val publication = ready.publication
        validate(publication).let { if (it is AppResult.Failed) return it }
        if (!focus.current(focused)) return surfaceFailure(FailureKind.Conflict)
        val payload = publication.nativePayload(copy, silent = !allowAlert || !ready.newOriginal)
        val submitted = show(payload)
        if (submitted is AppResult.Failed) return submitted
        if (!focus.current(focused)) return surfaceFailure(FailureKind.Conflict)
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
        if (!focus.current(focused)) return surfaceFailure(FailureKind.Conflict)
        currentCoroutineContext().ensureActive()
        return AppResult.Success(CollaborationNotificationPublicationResult.Submitted)
    }
}
