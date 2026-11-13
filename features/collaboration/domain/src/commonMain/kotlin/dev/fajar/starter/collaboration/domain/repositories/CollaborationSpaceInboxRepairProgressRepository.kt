package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Owned encrypted finite-pass metadata; no transport, admission or scheduling policy. */
interface CollaborationSpaceInboxRepairProgressRepository {
    /** One owned metadata stream. Mapping failures can recover on a later checkpoint update. */
    fun observe(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceInboxRepairProgress?>>

    suspend fun current(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceInboxRepairProgress?>

    /** Exact state CAS. Null means a competing state or raw ownership change. */
    suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxRepairProgress?,
        replacement: CollaborationSpaceInboxRepairProgress,
    ): AppResult<CollaborationSpaceInboxRepairProgress?>
}
