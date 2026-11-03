package dev.fajar.starter.app.work

import dev.fajar.starter.app.notifications.CollaborationNotificationReplies
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Four-second device-input phase; at most two attempts, with durable seek before each one. */
class CollaborationNotificationInputRecoveryWorker(
    private val begin: BeginCollaborationNotificationInputRecovery,
    private val advance: AdvanceCollaborationNotificationInputRecovery,
    private val replies: CollaborationNotificationReplies,
) {
    private val execution = Mutex()

    suspend operator fun invoke(parent: CollaborationNativeParent): SyncResult {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return SyncResult.Retry(failure(FailureKind.Unavailable))
        return try {
            withTimeoutOrNull(4_000) { recover(parent) }
                ?: SyncResult.Retry(failure(FailureKind.Timeout))
        } finally {
            execution.unlock()
        }
    }

    private suspend fun recover(parent: CollaborationNativeParent): SyncResult {
        val begun = begin(parent)
        if (begun is AppResult.Failed) return SyncResult.Retry(begun.failure)
        var snapshot = (begun as AppResult.Success).value ?: return SyncResult.Complete
        if (snapshot.parent != parent)
            return SyncResult.Blocked(collaborationRecoveryParentChanged())
        repeat(2) {
            val state = snapshot.cursor.state
            val reference = state.references.getOrNull(state.nextIndex) ?: return@repeat
            val attempted =
                advance(snapshot, CollaborationNotificationInputRecoveryChange.Attempt(reference))
            if (attempted is AppResult.Failed) return SyncResult.Retry(attempted.failure)
            snapshot = (attempted as AppResult.Success).value
            val outcome =
                withTimeoutOrNull(1_000) { replies.resumeProtected(reference) }
                    ?: AppResult.Failed(failure(FailureKind.Timeout))
            currentCoroutineContext().ensureActive()
            // Only explicit admission denials can settle without a retry. In particular a
            // cryptographic/storage failure cannot be guessed to mean "another account".
            val retry =
                outcome is AppResult.Failed &&
                    outcome.failure.kind !in
                        setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
            val settled =
                advance(snapshot, CollaborationNotificationInputRecoveryChange.Settle(retry))
            if (settled is AppResult.Failed) return SyncResult.Retry(settled.failure)
            snapshot = (settled as AppResult.Success).value
        }
        if (snapshot.cursor.state.nextIndex < snapshot.cursor.state.references.size)
            return SyncResult.Retry()
        val finished = advance(snapshot, CollaborationNotificationInputRecoveryChange.Finish)
        if (finished is AppResult.Failed) return SyncResult.Retry(finished.failure)
        val state = (finished as AppResult.Success).value.cursor.state
        return when {
            state.retryRequired -> SyncResult.Retry(failure(FailureKind.Unavailable))
            state.continuationRequired -> SyncResult.Retry()
            else -> SyncResult.Complete
        }
    }

    private fun failure(kind: FailureKind) =
        Failure(
            kind,
            "Chat notification input recovery is pending.",
            code = "client.collaboration_notification_input_worker_unavailable",
        )
}
