package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Application entry point inside the delivery worker's deadline, independent of chat routes. */
class CollaborationDraftRecovery(
    private val discover: ReadCollaborationDraftRecovery,
    private val advance: AdvanceCollaborationDraftRecovery,
    private val queue: QueueCollaborationText,
    private val confirm: ConfirmCollaborationDraftSend,
    private val suspendSend: SuspendCollaborationDraftSend,
    private val recoverComposer: RecoverCollaborationComposer,
) {
    suspend operator fun invoke(): SyncResult {
        val composer =
            traceCollaborationDelivery(initial = CollaborationDeliveryStage.RecoverComposer) {
                recoverComposer()
            }
        currentCoroutineContext().ensureActive()
        val legacy =
            traceCollaborationDeliveryWork(CollaborationDeliveryStage.RecoverLegacyDrafts) {
                recover(discover())
            }
        return combined(composer, legacy)
    }

    suspend operator fun invoke(scope: CollaborationLocalScope): SyncResult {
        val composer =
            traceCollaborationDelivery(initial = CollaborationDeliveryStage.RecoverComposer) {
                recoverComposer(scope)
            }
        currentCoroutineContext().ensureActive()
        val legacy =
            traceCollaborationDeliveryWork(CollaborationDeliveryStage.RecoverLegacyDrafts) {
                recover(discover(scope))
            }
        return combined(composer, legacy)
    }

    private fun combined(composer: AppResult<Boolean>, legacy: SyncResult): SyncResult {
        val current =
            when (composer) {
                is AppResult.Failed -> recoveryFailure(composer.failure)
                is AppResult.Success ->
                    if (composer.value) SyncResult.Retry() else SyncResult.Complete
            }
        // The worker still drains materialized messages before returning this continuation.
        // Pending independent work needs another pass even when one source has a local hold.
        return when {
            legacy is SyncResult.Retry -> legacy
            current is SyncResult.Retry -> current
            legacy is SyncResult.Blocked -> legacy
            else -> current
        }
    }

    private suspend fun recover(found: AppResult<CollaborationDraftRecoveryWork?>): SyncResult {
        currentCoroutineContext().ensureActive()
        if (found is AppResult.Failed) return recoveryFailure(found.failure)
        val work = (found as AppResult.Success).value ?: return SyncResult.Complete
        var cursor = work.cursor
        var retry: Failure? = null
        var blocked: Failure? = null
        for (draft in work.drafts) {
            val moved = advance(work.copy(cursor = cursor), draft.conversationId)
            currentCoroutineContext().ensureActive()
            if (moved is AppResult.Failed) return recoveryFailure(moved.failure)
            cursor = (moved as AppResult.Success).value
            val queued =
                queue(
                    work.scope,
                    draft.conversationId,
                    requireNotNull(draft.submissionId),
                    draft.text,
                    work.access,
                    draft.replyToEventId,
                )
            currentCoroutineContext().ensureActive()
            var failed = (queued as? AppResult.Failed)?.failure
            if (failed == null) {
                val confirmed = confirm(work.scope, draft, work.access)
                currentCoroutineContext().ensureActive()
                failed = (confirmed as? AppResult.Failed)?.failure
            } else if (
                failed.code in
                    setOf(
                        "client.collaboration_conversation_unavailable",
                        "client.collaboration_message_changed",
                    )
            ) {
                // Reproduce the blocking facts before holding the original. A successful racing
                // admission or changed draft must never be treated as an unsent cancellation.
                val reviewed = suspendSend(work.scope, draft, work.access)
                currentCoroutineContext().ensureActive()
                when (reviewed) {
                    is AppResult.Failed -> failed = reviewed.failure
                    is AppResult.Success -> {
                        if (
                            reviewed.value?.reviewRequired == true ||
                                reviewed.value?.submissionId != draft.submissionId
                        )
                            continue
                        retry = retry ?: failed
                        continue
                    }
                }
            }
            failed?.let {
                when (recoveryFailure(it)) {
                    is SyncResult.Retry -> retry = retry ?: it
                    is SyncResult.Blocked -> blocked = blocked ?: it
                    SyncResult.Complete -> Unit
                }
            }
        }
        val remaining = discover(work.scope)
        currentCoroutineContext().ensureActive()
        if (remaining is AppResult.Failed) return recoveryFailure(remaining.failure)
        // A shared storage/authority failure needs a later trigger. Confirmed per-draft holds are
        // durable and excluded; they cannot spin through this cursor or starve another room.
        blocked?.let {
            return SyncResult.Blocked(it)
        }
        return if ((remaining as AppResult.Success).value != null) SyncResult.Retry(retry)
        else SyncResult.Complete
    }

    private fun recoveryFailure(failure: Failure): SyncResult =
        if (
            failure.code in
                setOf(
                    "client.command_queue_full",
                    "client.collaboration_draft_changed",
                    "client.collaboration_draft_busy",
                    "client.collaboration_composer_busy",
                )
        )
            SyncResult.Retry(failure)
        else syncFailure(failure)
}
