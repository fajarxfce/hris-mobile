package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Global local-history progress; raw index/source guards share its single bounded transaction. */
interface CollaborationSpaceInboxHistoryProgressRepository {
    suspend fun current(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceInboxHistoryCheckpoint?>

    /** Metadata only. Never reads visible inbox entries or creates missing encryption keys. */
    fun observeDestination(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceInboxVersion?>>

    /**
     * Writes the expected global checkpoint beside exactly one destination or source Check. Null
     * means contention; a lost committed response requires reading current progress. The admitted
     * operation chooses a destination guard for trigger/page capture and final completion, or an
     * exact source guard for a successful item acknowledgement. It never expands the raw
     * two-operation bound or publishes another history/repair signal.
     */
    suspend fun mutate(
        access: CollaborationSpaceLocalAccess,
        mutation: CollaborationSpaceInboxHistoryMutation,
    ): AppResult<CollaborationSpaceInboxHistoryCheckpoint?>
}
