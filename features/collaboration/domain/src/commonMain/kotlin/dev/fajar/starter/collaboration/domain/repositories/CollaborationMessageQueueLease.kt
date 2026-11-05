package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One exclusive native writer, initially paused. Closing it pauses without deleting messages. */
interface CollaborationMessageQueueLease {
    val owner: CollaborationOwner
    val device: CollaborationMessageDevice

    /** Complete captured per-room IDs, not a possibly unhydrated timeline or a quiet-time guess. */
    suspend fun snapshot(): AppResult<Set<String>>

    /**
     * Describes exact outgoing content under this owned SDK identity, without enqueue or delivery.
     */
    suspend fun prepareText(
        logicalId: String,
        text: String,
        replyToEventId: String? = null,
    ): AppResult<CollaborationMessageOrigin>

    /** The caller durably records Staging first; cancellation can leave this enqueue committed. */
    suspend fun enqueue(
        logicalId: String,
        text: String,
        origin: CollaborationMessageOrigin,
        replyToEventId: String? = null,
    ): AppResult<Unit>

    /**
     * One bounded delivery window after live admission and durable mapping of every queued ID. Only
     * this room can resume. Missing native IDs never imply a successful application send.
     */
    suspend fun resume(mappedTransactions: Set<String>): AppResult<CollaborationQueueDelivery>

    /** Waiter cancellation cannot release the exclusive writer before native work has drained. */
    suspend fun close(): AppResult<Unit>
}
