package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpaceOriginalConversationRemoteSource {
    suspend fun list(
        sessionId: String,
        spaceId: String,
        after: CollaborationOriginalConversationPositionDto?,
        limit: Int,
    ): CollaborationSpaceOriginalConversationPageDto

    suspend fun get(
        sessionId: String,
        spaceId: String,
        companyId: String,
        conversationId: String,
    ): CollaborationSpaceOriginalConversationDto
}
