package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Native control-plane I/O; use cases own admission, durable commands and retries. */
interface CollaborationConversationRepository {
    suspend fun participants(
        owner: CollaborationOwner,
        query: String,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationPage<CollaborationParticipant>>

    suspend fun list(
        owner: CollaborationOwner,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationPage<CollaborationDirectConversation>>

    suspend fun get(
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationDirectConversation>

    suspend fun room(
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationDirectRoom>

    suspend fun open(
        owner: CollaborationOwner,
        command: CollaborationOpenDirect,
    ): AppResult<CollaborationSetupReceipt>

    suspend fun requestRoom(
        owner: CollaborationOwner,
        command: CollaborationRequestDirectRoom,
    ): AppResult<CollaborationSetupReceipt>
}
