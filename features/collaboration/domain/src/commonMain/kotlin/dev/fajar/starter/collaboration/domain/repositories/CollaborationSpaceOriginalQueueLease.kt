package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One original physical writer under its current logical owner; no new message capability. */
interface CollaborationSpaceOriginalQueueLease {
    val runtime: CollaborationSpaceRuntimeReady
    val binding: CollaborationSetupBinding
    val device: CollaborationMessageDevice

    suspend fun snapshot(): AppResult<CollaborationSpaceOriginalQueueSnapshot>

    /** Caller supplies immutable completion proof. AlreadyAbsent never establishes an ACK. */
    suspend fun settleConfirmed(
        original: CollaborationQueuedOriginal,
        eventId: String,
    ): AppResult<CollaborationQueueSettlement>

    /**
     * Rechecks the complete exact paused inventory before resuming; partial sets are rejected. The
     * owning use case must match all retained commands and admit the current recipient/room. A late
     * observer may journal only captured original facts; it must gate UI publication on its own
     * active invocation. Cancellation does not erase a late native acknowledgement.
     */
    suspend fun resume(
        expected: CollaborationSpaceOriginalQueueSnapshot,
        onObserved: suspend (CollaborationQueueDelivery) -> AppResult<Unit>,
    ): AppResult<CollaborationQueueDelivery>

    /** Pauses and drains the owned operation/callback before the original writer is released. */
    suspend fun close(): AppResult<Unit>
}
