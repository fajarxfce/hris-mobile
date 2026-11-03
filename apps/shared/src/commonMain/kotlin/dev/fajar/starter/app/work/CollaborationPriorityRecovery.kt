package dev.fajar.starter.app.work

import dev.fajar.starter.app.notifications.CollaborationNotificationDelivery
import dev.fajar.starter.app.notifications.CollaborationNotificationPublicationResult
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** One local hint per bounded turn; the caller also owes the ordinary fair company sweep. */
class CollaborationPriorityRecovery(
    private val read: ReadCollaborationPushPriority,
    private val complete: CompleteCollaborationPushPriority,
    private val admit: AcquireCollaborationBackgroundAdmission,
    private val incoming: CollaborationIncomingRecovery,
    private val notifications: CollaborationNotificationDelivery,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(parent: CollaborationNativeParent): SyncResult {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(20.seconds) { process(parent) }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Chat recovery is pending.",
                    code = "client.collaboration_priority_timeout",
                )
            )
    }

    private suspend fun process(parent: CollaborationNativeParent): SyncResult {
        val loaded = read()
        currentCoroutineContext().ensureActive()
        if (loaded is AppResult.Failed) return collaborationWorkFailure(loaded.failure)
        val snapshot = (loaded as AppResult.Success).value
        if (snapshot.parent != parent)
            return SyncResult.Blocked(collaborationRecoveryParentChanged())
        val hint = snapshot.priority?.hints?.firstOrNull() ?: return SyncResult.Complete
        val now = clock.now().epochSeconds
        if (now < hint.issuedAt) return pending("not_due")
        if (hint.roomId != null && now >= hint.issuedAt && now < hint.expiresAt) {
            val granted = admit.forHint(parent, hint.companyId, hint.deviceId)
            currentCoroutineContext().ensureActive()
            if (granted is AppResult.Failed) return collaborationWorkFailure(granted.failure)
            val received = incoming.priority((granted as AppResult.Success).value, hint)
            currentCoroutineContext().ensureActive()
            if (received.result !is SyncResult.Complete) return received.result
            val candidate = received.candidate ?: return pending("candidate_pending")
            if (
                candidate.companyId != hint.companyId ||
                    candidate.roomId != hint.roomId ||
                    candidate.eventId != hint.matrixEventId
            )
                return pending("candidate_changed")
            // Incoming has joined its timeline/runtime before the process receives this request.
            val submitted = notifications.publish(parent, candidate, allowAlert = true)
            currentCoroutineContext().ensureActive()
            when (submitted) {
                is AppResult.Failed ->
                    return if (
                        collaborationContentResponseClosesRead(submitted.failure) ||
                            submitted.failure.kind == FailureKind.Validation
                    )
                        SyncResult.Blocked(submitted.failure)
                    else SyncResult.Retry(submitted.failure)
                is AppResult.Success ->
                    when (submitted.value) {
                        CollaborationNotificationPublicationResult.Submitted,
                        CollaborationNotificationPublicationResult.Suppressed,
                        CollaborationNotificationPublicationResult.SuppressionRecorded,
                        CollaborationNotificationPublicationResult.Unchanged -> Unit
                        else -> return pending("notification_pending")
                    }
            }
        }
        val saved = complete(snapshot, hint)
        currentCoroutineContext().ensureActive()
        if (saved is AppResult.Failed) return collaborationWorkFailure(saved.failure)
        return if ((saved as AppResult.Success).value && snapshot.priority!!.hints.size == 1)
            SyncResult.Complete
        else SyncResult.Retry()
    }

    private fun pending(suffix: String) =
        SyncResult.Retry(
            Failure(
                FailureKind.Unavailable,
                "Chat recovery is pending.",
                code = "client.collaboration_priority_$suffix",
            )
        )
}
