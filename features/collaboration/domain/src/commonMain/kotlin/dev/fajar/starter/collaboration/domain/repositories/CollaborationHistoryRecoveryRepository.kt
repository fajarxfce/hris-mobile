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

    /**
     * Advances one exact finite attempted page, permitting primary changes and a later request.
     * Verifies the still-pending indexed interval before crossing it; a null cursor alone is not
     * EOF evidence. The caller owns admission and the number of attempted unresolved outcomes.
     * False means contention or a changed pass, for the operation owner to read back/reselect.
     */
    suspend fun advance(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        attempted: CollaborationHistoryReferences,
        unresolved: Int,
        limit: Int = 16,
    ): AppResult<Boolean>

    suspend fun advance(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        attempted: CollaborationHistoryReferences,
        unresolved: Int,
        limit: Int = 16,
    ): AppResult<Boolean>

    /**
     * Atomically resolves an admitted subset and advances one exact finite recovery page. The
     * caller proves content outcomes and owns live admission. Immutable source identities, indexed
     * interval coverage and the active pass are verified before one checkpoint CAS. Independent
     * primary progress and later requests remain. A replay after advancement returns false; callers
     * own readback/reselection after unknown replies. No global work is completed.
     */
    suspend fun complete(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        attempted: CollaborationHistoryReferences,
        resolved: List<CollaborationHistoryReference>,
        limit: Int = 16,
    ): AppResult<Boolean>

    suspend fun complete(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        attempted: CollaborationHistoryReferences,
        resolved: List<CollaborationHistoryReference>,
        limit: Int = 16,
    ): AppResult<Boolean>
}
