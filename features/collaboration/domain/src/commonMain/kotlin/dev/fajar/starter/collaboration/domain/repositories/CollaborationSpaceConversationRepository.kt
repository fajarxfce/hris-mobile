package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceConversationRepository {
    suspend fun list(
        owner: CollaborationSpaceOwner,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceConversationPage>

    suspend fun get(
        owner: CollaborationSpaceOwner,
        conversationId: String,
    ): AppResult<CollaborationSpaceConversation>

    /** Call only with the retained original operation and peer after current native admission. */
    suspend fun open(
        owner: CollaborationSpaceOwner,
        operation: String,
        peerAccountId: String,
    ): AppResult<CollaborationSetupReceipt>
}
