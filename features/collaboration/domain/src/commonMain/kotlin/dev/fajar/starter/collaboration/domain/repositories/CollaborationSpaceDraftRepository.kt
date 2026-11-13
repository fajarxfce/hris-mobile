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

    /** Source slot and ready inbox publication commit together under both original checkpoints. */
    suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
        inbox: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean>
}
