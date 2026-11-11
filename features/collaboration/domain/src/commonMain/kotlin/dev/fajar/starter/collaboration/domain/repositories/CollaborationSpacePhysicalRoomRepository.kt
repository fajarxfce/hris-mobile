package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Finite native metadata reads. Caller owns admission, fallback and durable retention. */
interface CollaborationSpacePhysicalRoomRepository {
    suspend fun published(
        owner: CollaborationSpaceOwner,
        serverName: String,
        roomId: String,
    ): AppResult<CollaborationSpacePhysicalRoomView>

    suspend fun original(
        owner: CollaborationSpaceOwner,
        companyId: String,
        serverName: String,
        roomId: String,
    ): AppResult<CollaborationSpaceOriginalConversation>
}
