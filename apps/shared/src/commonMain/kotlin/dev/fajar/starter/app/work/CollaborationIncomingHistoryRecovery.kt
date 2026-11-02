package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The caller owns the finite background runtime, timeline, deadline and single incoming turn. */
class CollaborationIncomingHistoryRecovery(
    private val acquireOriginals: AcquireCollaborationHistory,
    private val acquireProvider: AcquireCollaborationProviderHistory,
    private val readReferences: ReadCollaborationHistoryReferences,
    private val reconcileReferences: ReconcileCollaborationHistoryReferences,
    private val advanceReferences: AdvanceCollaborationIncomingReferences,
) {
    suspend operator fun invoke(
        scope: CollaborationOperationScope.Background,
        key: CollaborationHistoryTraversal,
    ): SyncResult {
        val latest = acquireOriginals(scope, key.deviceId, key.conversationId)
        currentCoroutineContext().ensureActive()
        if (latest is AppResult.Failed) return collaborationWorkFailure(latest.failure)
        val local = CollaborationLocalScope.Captured(scope.admission.owner.localListAccess())
        val loaded = readReferences(local, key, limit = 1)
        if (loaded is AppResult.Failed) return collaborationWorkFailure(loaded.failure)
        val previous = (loaded as AppResult.Success).value.progress
        val origin =
            if (previous?.exhausted == true) CollaborationHistoryPageOrigin.Latest
            else CollaborationHistoryPageOrigin.SavedPosition
        val acquired = acquireProvider(scope, key.deviceId, key.conversationId, origin, key.purpose)
        currentCoroutineContext().ensureActive()
        if (acquired is AppResult.Failed) return collaborationWorkFailure(acquired.failure)
        return pending(scope, local, key, (acquired as AppResult.Success).value.exhausted)
    }

    private suspend fun pending(
        scope: CollaborationOperationScope.Background,
        local: CollaborationLocalScope.Captured,
        key: CollaborationHistoryTraversal,
        providerEnd: Boolean,
    ): SyncResult {
        val observed = readReferences(local, key, limit = 1)
        if (observed is AppResult.Failed) return collaborationWorkFailure(observed.failure)
        val after = (observed as AppResult.Success).value.progress?.incoming?.pendingAfter
        val known = reconcileReferences(local, key, after)
        if (known is AppResult.Failed) return collaborationWorkFailure(known.failure)
        val read = readReferences(local, key, after)
        if (read is AppResult.Failed) return collaborationWorkFailure(read.failure)
        val page =
            boundedCollaborationIncomingReferences((read as AppResult.Success).value)
                ?: return SyncResult.Retry(pendingFailure())
        val problem = acquirePage(scope, key, page)
        currentCoroutineContext().ensureActive()
        if (problem != null && collaborationContentResponseClosesRead(problem))
            return collaborationWorkFailure(problem)
        val reconciled = reconcileReferences(local, key, after)
        if (reconciled is AppResult.Failed) return collaborationWorkFailure(reconciled.failure)
        val remaining = readReferences(local, key, after)
        if (remaining is AppResult.Failed) return collaborationWorkFailure(remaining.failure)
        val unresolved =
            (remaining as AppResult.Success).value.events.map { it.event.eventId }.toSet()
        val missing = page.events.any { potentialMessage(it) && it.event.eventId in unresolved }
        val saved = advanceReferences(local, key, page, retryRequired = missing || problem != null)
        currentCoroutineContext().ensureActive()
        if (saved is AppResult.Failed) return collaborationWorkFailure(saved.failure)
        if (problem != null) return collaborationWorkFailure(problem)
        if (page.after != null) return SyncResult.Retry()
        if (missing || page.progress?.incoming?.pendingRetryRequired == true)
            return SyncResult.Retry(pendingFailure())
        return if (providerEnd) SyncResult.Complete else SyncResult.Retry()
    }

    private suspend fun acquirePage(
        scope: CollaborationOperationScope.Background,
        key: CollaborationHistoryTraversal,
        page: CollaborationHistoryReferences,
    ): Failure? {
        val candidates = page.events.filter(::potentialMessage)
        val anchors =
            listOfNotNull(candidates.lastOrNull(), candidates.firstOrNull())
                .map { it.event.eventId }
                .distinct()
        for (anchor in anchors) {
            val result = acquireOriginals(scope, key.deviceId, key.conversationId, anchor)
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) return result.failure
        }
        return null
    }

    private fun potentialMessage(reference: CollaborationHistoryReference) =
        reference.event.type in setOf("m.room.encrypted", "m.room.message", "m.sticker")

    private fun pendingFailure() =
        Failure(
            FailureKind.Unavailable,
            "Chat history is pending.",
            code = "client.collaboration_history_pending",
        )
}
