package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ChatThreadHistoryPosition(
    val after: String? = null,
    val storedEnd: Boolean = false,
) {
    override fun toString() = "ChatThreadHistoryPosition([redacted])"
}

internal data class ChatThreadHistoryBatch(
    val position: ChatThreadHistoryPosition,
    val scanned: Boolean,
    val unresolved: Boolean,
)

/**
 * Screen-owned, finite coordination. The caller owns cancellation; no transport or child leases.
 */
internal class ChatThreadHistoryPager(
    private val acquireProvider: AcquireCollaborationProviderHistory,
    private val acquireOriginals: AcquireCollaborationHistory,
    private val readReferences: ReadCollaborationHistoryReferences,
    private val reconcileReferences: ReconcileCollaborationHistoryReferences,
) {
    var recoveryAnchor: String? = null
        private set

    /** Restore an owned end marker without contacting the provider or replaying decryption. */
    suspend fun exhausted(
        workspaceId: String,
        key: CollaborationHistoryTraversal,
    ): AppResult<Boolean> {
        var after: String? = null
        var snapshot: CollaborationHistoryProgress? = null
        var previousSequence = -1L
        while (true) {
            val result = readReferences(workspaceId, key, after)
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) return result
            val page = (result as AppResult.Success).value
            val progress = page.progress ?: return AppResult.Success(false)
            if (!progress.exhausted) return AppResult.Success(false)
            if (snapshot == null) snapshot = progress
            else if (!sameTraversal(snapshot, progress)) return AppResult.Success(false)
            // Room state has no canonical message original to acknowledge. Keep those references,
            // but do not advertise them as older messages. Walk only this finite local snapshot;
            // an unattempted message on a later page must still keep history available.
            if (page.events.any(::potentialMessage)) return AppResult.Success(false)
            if (page.events.isNotEmpty()) {
                if (page.events.first().sequence <= previousSequence)
                    return AppResult.Success(false)
                previousSequence = page.events.last().sequence
            }
            after = page.after ?: return AppResult.Success(true)
        }
    }

    suspend fun load(
        workspaceId: String,
        key: CollaborationHistoryTraversal,
        position: ChatThreadHistoryPosition,
    ): AppResult<ChatThreadHistoryBatch> {
        recoveryAnchor = null
        val acknowledged = reconcileReferences(workspaceId, key, position.after)
        currentCoroutineContext().ensureActive()
        if (acknowledged is AppResult.Failed) return acknowledged
        var loaded = readReferences(workspaceId, key, position.after)
        currentCoroutineContext().ensureActive()
        if (loaded is AppResult.Failed) return loaded
        var page = (loaded as AppResult.Success).value
        if (page.progress?.exhausted != true && (position.storedEnd || page.events.isEmpty())) {
            val result = acquireProvider(workspaceId, key.deviceId, key.conversationId)
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) return result
            loaded = readReferences(workspaceId, key, position.after)
            currentCoroutineContext().ensureActive()
            if (loaded is AppResult.Failed) return loaded
            page = (loaded as AppResult.Success).value
        }
        // Newly acquired references can already have durable originals from incoming recovery.
        // Reconcile the bounded page before spending a native focus. Keep this page's cursor:
        // removing resolved rows can expose the next local page in the subsequent read.
        val stored = reconcileReferences(workspaceId, key, position.after)
        currentCoroutineContext().ensureActive()
        if (stored is AppResult.Failed) return stored
        val outstanding = readReferences(workspaceId, key, position.after)
        currentCoroutineContext().ensureActive()
        if (outstanding is AppResult.Failed) return outstanding
        val outstandingIds =
            (outstanding as AppResult.Success).value.events.map { it.event.eventId }.toSet()
        // Endpoints cover only missing originals in this bounded slice. A provider reference
        // alone does not establish that another decryption window is necessary.
        val candidates =
            page.events.filter { potentialMessage(it) && it.event.eventId in outstandingIds }
        val anchors =
            listOfNotNull(candidates.lastOrNull(), candidates.firstOrNull())
                .map { it.event.eventId }
                .distinct()
        for (anchor in anchors) {
            val result = acquireOriginals(workspaceId, key.deviceId, key.conversationId, anchor)
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) {
                if (result.failure.code == "client.collaboration_history_focus_capacity")
                    recoveryAnchor = anchor
                return result
            }
        }
        val reconciled = reconcileReferences(workspaceId, key, position.after)
        currentCoroutineContext().ensureActive()
        if (reconciled is AppResult.Failed) return reconciled
        val pending = readReferences(workspaceId, key, position.after)
        currentCoroutineContext().ensureActive()
        if (pending is AppResult.Failed) return pending
        val remaining = (pending as AppResult.Success).value
        val unresolvedIds = remaining.events.map { it.event.eventId }.toSet()
        val missingMessages =
            page.events.any { it.event.eventId in unresolvedIds && potentialMessage(it) }
        // Reconciliation can change revision. A concurrently replaced/extended provider traversal
        // must instead restart the local scan, without treating an old snapshot as its end.
        val stable = sameTraversal(page.progress, remaining.progress)
        return AppResult.Success(
            ChatThreadHistoryBatch(
                if (stable)
                    ChatThreadHistoryPosition(page.after ?: position.after, page.after == null)
                else ChatThreadHistoryPosition(),
                stable && page.progress?.exhausted == true && page.after == null,
                missingMessages,
            )
        )
    }

    private fun potentialMessage(reference: CollaborationHistoryReference) =
        reference.event.type in setOf("m.room.encrypted", "m.room.message", "m.sticker")

    private fun sameTraversal(a: CollaborationHistoryProgress?, b: CollaborationHistoryProgress?) =
        a?.generation == b?.generation && a?.references == b?.references && a?.next == b?.next
}
