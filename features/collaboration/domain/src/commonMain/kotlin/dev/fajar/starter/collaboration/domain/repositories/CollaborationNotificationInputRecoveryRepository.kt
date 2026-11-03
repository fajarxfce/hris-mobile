package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Account-encrypted progress only; unverified device text is never stored in this cursor. */
interface CollaborationNotificationInputRecoveryRepository {
    suspend fun current(
        access: CollaborationRecoveryAccess
    ): AppResult<CollaborationNotificationInputRecoveryCursor?>

    /** Exact-version CAS; null means contention. Never creates a replacement history key. */
    suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationInputRecoveryCursor?,
        state: CollaborationNotificationInputRecoveryState,
    ): AppResult<CollaborationNotificationInputRecoveryCursor?>
}
