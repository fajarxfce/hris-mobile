package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationRoomRecoveryRepository {
    suspend fun current(
        access: CollaborationLocalAccess
    ): AppResult<CollaborationRoomRecoveryProgress?>

    /** Null success means the captured checkpoint or active local account no longer matches. */
    suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationRoomRecoveryProgress?,
        position: CollaborationRoomRecoveryPosition,
    ): AppResult<CollaborationRoomRecoveryProgress?>
}
