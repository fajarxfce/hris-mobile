package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted local recipient slots; use cases own admission and any later canonical handoff. */
interface CollaborationSpaceRecipientDraftRepository {
    fun observe(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): Flow<AppResult<CollaborationSpaceRecipientDraft?>>

    suspend fun get(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): AppResult<CollaborationSpaceRecipientDraft?>

    /** Exact replay is unchanged; an empty value retains its revision against late writers. */
    suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientDraft?,
        replacement: CollaborationSpaceRecipientDraft,
    ): AppResult<Boolean>
}
