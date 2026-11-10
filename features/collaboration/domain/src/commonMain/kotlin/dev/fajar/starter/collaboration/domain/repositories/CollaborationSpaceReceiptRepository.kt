package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/**
 * Durable organization acknowledgements; current admission and state reconciliation are use-case
 * policy.
 */
interface CollaborationSpaceReceiptRepository {
    suspend fun read(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<List<CollaborationSpaceMessageReceipt>>

    /**
     * Unions 1..32 exact originals and their aliases with fresh recovery work in one transaction.
     * Identical replay leaves ciphertext and wake unchanged. False means a competing write,
     * ownership or work-capacity conflict; the owning caller rereads before deciding what remains.
     */
    suspend fun append(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        receipts: List<CollaborationSpaceMessageReceipt>,
    ): AppResult<Boolean>
}
