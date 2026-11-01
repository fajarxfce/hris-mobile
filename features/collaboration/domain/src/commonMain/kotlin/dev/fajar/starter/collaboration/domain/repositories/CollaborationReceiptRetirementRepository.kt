package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Exact journal cleanup; callers first retain completion and verify original history. */
interface CollaborationReceiptRetirementRepository {
    /**
     * Removes 1..32 exact observed receipts under the current owned checkpoint CAS. A changed alias
     * list, missing receipt, competing writer or changed account returns false. No retry. Retains
     * an authenticated empty checkpoint when the last receipt is removed.
     */
    suspend fun removeReceipts(
        access: CollaborationLocalAccess,
        conversationId: String,
        expected: List<CollaborationMessageReceipt>,
    ): AppResult<Boolean>
}
