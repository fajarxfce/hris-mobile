package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Durable acknowledgement journal. Callers own admission and reconciliation with command state. */
interface CollaborationReceiptRepository {
    /**
     * Owned offline facts; at most 128 retained outbound messages and 512 event aliases per room.
     */
    suspend fun read(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<List<CollaborationMessageReceipt>>

    /**
     * Atomically unions aliases without changing an original intent/device/transaction. Batches
     * contain 1..32 distinct intents, each with 1..128 aliases. False means a competing write or
     * ownership change; callers reread before deciding what remains. No automatic retry or
     * deletion.
     */
    suspend fun append(
        access: CollaborationLocalAccess,
        conversationId: String,
        receipts: List<CollaborationMessageReceipt>,
    ): AppResult<Boolean>
}
