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

    /**
     * Atomically retains immutable conversation/room indexes and fresh recovery work. A consumer
     * holding an older wake cannot acknowledge this write. Capacity or revision conflicts change
     * neither binding nor wake. Identical retention is a no-op, including its recovery generation.
     * A lost response requires exact readback; scheduling belongs to the owning use case.
     */
    suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        binding: CollaborationSpaceRoomBinding,
    ): AppResult<Boolean>
}
