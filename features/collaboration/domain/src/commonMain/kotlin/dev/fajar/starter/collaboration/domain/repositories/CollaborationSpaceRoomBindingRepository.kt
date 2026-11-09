package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Account-owned encrypted facts, independent of current login or provider credentials. */
interface CollaborationSpaceRoomBindingRepository {
    suspend fun getByConversation(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationSpaceRoomBinding?>

    suspend fun findByRoom(
        access: CollaborationSpaceLocalAccess,
        serverName: String,
        roomId: String,
    ): AppResult<CollaborationSpaceRoomBinding?>

    /** Atomic immutable conversation/room indexes; identical retention is a no-op. */
    suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        binding: CollaborationSpaceRoomBinding,
    ): AppResult<Boolean>
}
