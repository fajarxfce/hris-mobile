package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** One exclusive native writer, initially paused. Closing it pauses without deleting messages. */
interface CollaborationMessageQueueLease {
    val owner: CollaborationOwner
    val device: CollaborationMessageDevice

    /** Complete captured per-room IDs, not a possibly unhydrated timeline or a quiet-time guess. */
    suspend fun snapshot(): AppResult<Set<String>>

    /** Complete original content from a paused, quiescent native queue; no timeline inference. */
    suspend fun originals(): AppResult<List<CollaborationQueuedOriginal>> =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "The original message is unavailable.",
                code = "client.collaboration_queue_unresolved",
            )
        )

    /** Caller supplies immutable application completion proof; never cancels or redacts a send. */
    suspend fun settleConfirmed(
        original: CollaborationQueuedOriginal,
        eventId: String,
    ): AppResult<CollaborationQueueSettlement> =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "The original message is unavailable.",
                code = "client.collaboration_queue_unresolved",
            )
        )

    /**
     * Describes exact outgoing content under this owned SDK identity, without enqueue or delivery.
     */
    suspend fun prepareText(
        logicalId: String,
        text: String,
        replyToEventId: String? = null,
        forwarded: Boolean = false,
        editTarget: CollaborationEditTarget? = null,
    ): AppResult<CollaborationMessageOrigin>

    /** The caller durably records Staging first; cancellation can leave this enqueue committed. */
    suspend fun enqueue(
        logicalId: String,
        text: String,
        origin: CollaborationMessageOrigin,
        replyToEventId: String? = null,
        forwarded: Boolean = false,
        editTarget: CollaborationEditTarget? = null,
    ): AppResult<Unit>

    /**
     * One bounded delivery window after live admission and durable mapping of every queued ID. Only
     * this room can resume. Missing native IDs never imply a successful application send.
     * Cumulative observations are serialized within that window, before the final result. A failed
     * observer stops delivery. The owned native operation may outlive its cancelled waiter; an
     * observer must retain only captured-account facts and gate publication on its original caller.
     * Closing the lease drains the operation and its observer before releasing the writer.
     */
    suspend fun resume(
        mappedTransactions: Set<String>,
        onObserved: suspend (CollaborationQueueDelivery) -> AppResult<Unit> = {
            AppResult.Success(Unit)
        },
    ): AppResult<CollaborationQueueDelivery>

    /** Waiter cancellation cannot release the exclusive writer before native work has drained. */
    suspend fun close(): AppResult<Unit>
}
