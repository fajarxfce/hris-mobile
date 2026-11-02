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

    suspend fun summaries(
        owner: CollaborationOwner,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationPage<CollaborationDirectSummary>>

    suspend fun summary(
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationDirectSummary>

    /** Current server-side participant lookup; an incoming room ID grants no access by itself. */
    suspend fun summaryByRoom(
        owner: CollaborationOwner,
        roomId: String,
    ): AppResult<CollaborationDirectSummary>

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
