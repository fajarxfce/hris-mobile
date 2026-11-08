package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Account-owned encrypted observations, separate from message commands and live admission. */
interface CollaborationRecipientObservationRepository {
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): Flow<AppResult<CollaborationRecipientObservation?>>

    suspend fun get(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationRecipientObservation?>

    /** Exact replacement replay succeeds; a different complete previous value returns false. */
    suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationRecipientObservation?,
        replacement: CollaborationRecipientObservation,
    ): AppResult<Boolean>
}
