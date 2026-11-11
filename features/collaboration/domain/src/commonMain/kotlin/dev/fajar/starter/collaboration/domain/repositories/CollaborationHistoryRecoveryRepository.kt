package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/**
 * Conditional recovery-section storage in an existing incoming checkpoint. No partition, key,
 * content, reference or provider work is created. Callers own admission and pass/page policy.
 *
 * Independent primary appends/resolution may advance revision and reference count. A competing
 * recovery section returns false; an exact already-persisted replacement returns true without
 * another write. Unknown write outcomes remain failures for the owning operation to confirm.
 */
interface CollaborationHistoryRecoveryRepository {
    suspend fun replace(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        recovery: CollaborationHistoryRecoveryPosition,
    ): AppResult<Boolean>

    suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        recovery: CollaborationHistoryRecoveryPosition,
    ): AppResult<Boolean>
}
