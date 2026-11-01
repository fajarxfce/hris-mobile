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
) {
    suspend operator fun invoke(): SyncResult {
        val found = discover()
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
                    work.workspaceId,
                    draft.conversationId,
                    requireNotNull(draft.submissionId),
                    draft.text,
                    work.access,
                )
            currentCoroutineContext().ensureActive()
            var failed = (queued as? AppResult.Failed)?.failure
            if (failed == null) {
                val confirmed = confirm(work.workspaceId, draft, work.access)
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
                val reviewed = suspendSend(work.workspaceId, draft, work.access)
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
        val remaining = discover()
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
            failure.code in setOf("client.command_queue_full", "client.collaboration_draft_changed")
        )
            SyncResult.Retry(failure)
        else syncFailure(failure)
}
