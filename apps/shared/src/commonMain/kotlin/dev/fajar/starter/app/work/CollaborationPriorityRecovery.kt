package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
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
        if (hint.roomId != null && now >= hint.issuedAt && now < hint.expiresAt) {
            val granted = admit.forHint(parent, hint.companyId, hint.deviceId)
            currentCoroutineContext().ensureActive()
            val received =
                when (granted) {
                    is AppResult.Failed -> collaborationWorkFailure(granted.failure)
                    is AppResult.Success -> incoming.priority(granted.value, hint)
                }
            currentCoroutineContext().ensureActive()
            if (received !is SyncResult.Complete) return received
            // Credential age and denied/uncertain acquisition retain this owned hint. Only an
            // acquired original/tombstone or scheduling expiry can complete it.

        }
        val saved = complete(snapshot, hint)
        currentCoroutineContext().ensureActive()
        if (saved is AppResult.Failed) return collaborationWorkFailure(saved.failure)
        return if ((saved as AppResult.Success).value && snapshot.priority!!.hints.size == 1)
            SyncResult.Complete
        else SyncResult.Retry()
    }
}
