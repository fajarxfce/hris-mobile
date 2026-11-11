package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Immutable original facts in the Group account cache; content keeps its Company namespace. */
interface CollaborationSpaceOriginalRoomMetadataRepository {
    suspend fun getByConversation(
        access: CollaborationSpaceLocalAccess,
        position: CollaborationOriginalConversationPosition,
    ): AppResult<CollaborationSpaceOriginalRoomMetadata?>

    suspend fun findByRoom(
        access: CollaborationSpaceLocalAccess,
        companyId: String,
        serverName: String,
        roomId: String,
    ): AppResult<CollaborationSpaceOriginalRoomMetadata?>

    /**
     * Atomically retains both immutable indexes, without scheduling work or replacing old facts. A
     * lost response requires exact readback by the owning use case. False means no write was
     * accepted; cancellation and uncertain failures never cause a blind second write.
     */
    suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        metadata: CollaborationSpaceOriginalRoomMetadata,
    ): AppResult<Boolean>
}
