@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A previously observed viewport, with exact retained originals and account ownership. */
internal data class ChatThreadReadSelection(
    val access: CollaborationLocalAccess,
    val conversationId: String,
    val roomId: String,
    val eventIds: List<String>,
) {
    override fun toString() = "ChatThreadReadSelection([redacted])"
}

/** Route-owned local work. Only the recovery worker sends provider metadata. */
internal class ChatThreadReadTracker(
    private val scope: CoroutineScope,
    private val read: ReadCollaborationReadState,
    private val mark: MarkCollaborationMessagesRead,
    private val recover: RequestCollaborationRecovery,
    private val onFailure: (Failure) -> Unit,
) {
    private val mutation = Mutex()
    private var selection: ChatThreadReadSelection? = null
    private var pending: ReadAttempt? = null
    private var job: Job? = null

    fun update(value: ChatThreadReadSelection?, retry: Boolean = false) {
        if (selection == value && !retry) return
        selection = value?.copy(eventIds = value.eventIds.toList())
        job?.cancel()
        job = null
        val captured = selection ?: return
        job =
            scope.launch {
                // A cancelled storage callback can still be finishing a commit. Never overlap it.
                mutation.withLock {
                    ensureActive()
                    if (selection == captured) process(captured)
                }
            }
    }

    fun clear() {
        update(null)
        pending = null
    }

    private suspend fun process(current: ChatThreadReadSelection) {
        val unfinished = pending
        if (unfinished != null) {
            val old = unfinished.selection
            if (
                old.access == current.access &&
                    old.conversationId == current.conversationId &&
                    old.roomId == current.roomId
            ) {
                if (!commit(unfinished)) return
            } else pending = null
        }
        val loaded =
            read(
                CollaborationLocalScope.Captured(current.access),
                current.conversationId,
                current.roomId,
                current.eventIds,
            )
        currentCoroutineContext().ensureActive()
        if (loaded is AppResult.Failed) {
            onFailure(loaded.failure)
            return
        }
        val retained = (loaded as AppResult.Success).value
        val unread = current.eventIds.filterNot(retained.readEventIds::contains)
        if (unread.isEmpty()) {
            if (retained.pending.isNotEmpty()) wake()
            return
        }
        val attempt =
            ReadAttempt(
                current.copy(eventIds = unread),
                CollaborationReadReceiptIntent(
                    Uuid.random().toString(),
                    unread.first(),
                    CollaborationReadReceiptVisibility.Private,
                ),
            )
        pending = attempt
        commit(attempt)
    }

    private suspend fun commit(attempt: ReadAttempt): Boolean {
        val selected = attempt.selection
        val result =
            mark(
                CollaborationLocalScope.Captured(selected.access),
                selected.conversationId,
                selected.roomId,
                selected.eventIds,
                attempt.intent,
                selected.access,
            )
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed) {
            onFailure(result.failure)
            return false
        }
        if (pending === attempt) pending = null
        wake()
        return true
    }

    private suspend fun wake() {
        // A failed enqueue never erases intent. Startup/periodic recovery rediscovers it.
        withTimeoutOrNull(1_500) { recover() }
        currentCoroutineContext().ensureActive()
    }

    private data class ReadAttempt(
        val selection: ChatThreadReadSelection,
        val intent: CollaborationReadReceiptIntent,
    )
}
