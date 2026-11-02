package dev.fajar.starter.app.work

import dev.fajar.starter.app.notifications.CollaborationNotificationReplies
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** One bounded account turn; durable cycle outcomes survive pages and process replacement. */
class CollaborationNotificationReplyRecoveryWorker(
    private val begin: BeginCollaborationNotificationReplyRecoveryCycle,
    private val discover: ReadCollaborationNotificationReplyRecovery,
    private val advance: AdvanceCollaborationNotificationReplyRecovery,
    private val settle: SettleCollaborationNotificationReplyRecoveryCycle,
    private val finish: FinishCollaborationNotificationReplyRecoveryCycle,
    private val replies: CollaborationNotificationReplies,
) {
    private val execution = Mutex()

    suspend operator fun invoke(parent: CollaborationNativeParent): SyncResult {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return SyncResult.Retry(failure(FailureKind.Unavailable))
        return try {
            withTimeoutOrNull(8_000) { recover(parent) }
                ?: SyncResult.Retry(failure(FailureKind.Timeout))
        } finally {
            execution.unlock()
        }
    }

    private suspend fun recover(parent: CollaborationNativeParent): SyncResult {
        val begun = begin(parent)
        if (begun is AppResult.Failed)
            return if (begun.failure.kind == FailureKind.Unauthorized)
                SyncResult.Blocked(begun.failure)
            else SyncResult.Retry(begun.failure)
        var snapshot = (begun as AppResult.Success).value ?: return SyncResult.Complete
        if (snapshot.parent != parent)
            return SyncResult.Blocked(collaborationRecoveryParentChanged())
        val found = discover()
        if (found is AppResult.Failed) return SyncResult.Retry(found.failure)
        val work = (found as AppResult.Success).value
        if (work != null) {
            if (work.parent != parent)
                return SyncResult.Blocked(collaborationRecoveryParentChanged())
            if (work.cursor != snapshot.cursor)
                return SyncResult.Retry(failure(FailureKind.Conflict))
            for (entry in work.entries) {
                val moved = advance(work, entry, snapshot.cursor)
                if (moved is AppResult.Failed) return SyncResult.Retry(moved.failure)
                snapshot = snapshot.copy(cursor = (moved as AppResult.Success).value)
                val result =
                    withTimeoutOrNull(2_000) { replies.resume(entry.id) }
                        ?: AppResult.Failed(failure(FailureKind.Timeout))
                currentCoroutineContext().ensureActive()
                // Confirmed company denials retain input for a later admitted wake. Other
                // failures, including validation/conflict, cannot silently retire a retry.
                val retry =
                    result is AppResult.Failed &&
                        result.failure.kind !in
                            setOf(FailureKind.AccessDenied, FailureKind.Unauthorized)
                val settled = settle(snapshot, retry)
                if (settled is AppResult.Failed) return SyncResult.Retry(settled.failure)
                snapshot = (settled as AppResult.Success).value
            }
            // Persisted outcomes are carried into the final page, including an interrupted
            // attempt. No wrapping or fresh cycle is performed in this invocation.
            if (work.hasMore) return SyncResult.Retry()
        }
        val finished = finish(snapshot)
        if (finished is AppResult.Failed) return SyncResult.Retry(finished.failure)
        return if ((finished as AppResult.Success).value.cursor.cycle?.retryRequired != false)
            SyncResult.Retry(failure(FailureKind.Unavailable))
        else SyncResult.Complete
    }

    private fun failure(kind: FailureKind) =
        Failure(
            kind,
            "Chat reply recovery is pending.",
            code = "client.collaboration_notification_reply_recovery_unavailable",
        )
}
