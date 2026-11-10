package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Exact cleanup after the owning use case retains completion and verifies original history. */
interface CollaborationSpaceReceiptRetirementRepository {
    /**
     * Removes only 1..32 unchanged receipts; an empty authenticated checkpoint remains. No retry.
     */
    suspend fun removeReceipts(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        expected: List<CollaborationSpaceMessageReceipt>,
    ): AppResult<Boolean>
}
