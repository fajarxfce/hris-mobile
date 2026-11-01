package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Durable intent, not a second transport queue. The native SDK remains the delivery owner. */
interface CollaborationMessageRepository {
    /** Bounded by the shared 128-command account capacity; observations never start delivery. */
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String?,
    ): Flow<AppResult<List<CollaborationMessageRecord>>>

    /** Exact lookup also returns authenticated Sent/Cancelled records after immutable archival. */
    suspend fun get(
        access: CollaborationLocalAccess,
        id: String,
    ): AppResult<CollaborationMessageRecord?>

    suspend fun insert(
        access: CollaborationLocalAccess,
        intent: CollaborationMessageIntent,
    ): AppResult<CollaborationMessageInsertion>

    suspend fun update(
        access: CollaborationLocalAccess,
        expected: CollaborationMessageRecord,
        state: CollaborationMessageState,
    ): AppResult<Boolean>
}
