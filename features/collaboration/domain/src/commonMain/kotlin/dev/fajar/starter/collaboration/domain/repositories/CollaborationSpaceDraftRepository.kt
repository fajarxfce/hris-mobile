package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted Group editing slots. Local ownership and live admission have separate owners. */
interface CollaborationSpaceDraftRepository {
    fun observe(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): Flow<AppResult<CollaborationDraft?>>

    suspend fun get(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationDraft?>

    /** Exact previous-slot CAS; an empty successor retains its revision to fence stale writers. */
    suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ): AppResult<Boolean>
}
