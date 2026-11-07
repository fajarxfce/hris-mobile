package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceRoomRepository {
    suspend fun get(
        conversation: CollaborationSpaceConversation
    ): AppResult<CollaborationSpaceRoomView>

    /** The caller owns durable intent, current admission and explicit terminal retry policy. */
    suspend fun request(
        conversation: CollaborationSpaceConversation,
        operation: String,
        request: CollaborationSpaceRoomRequest,
    ): AppResult<CollaborationSetupReceipt>
}
