package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpaceConversationRemoteSource {
    suspend fun list(
        sessionId: String,
        spaceId: String,
        after: String?,
        limit: Int,
    ): CollaborationSpaceConversationPageDto

    suspend fun get(
        sessionId: String,
        spaceId: String,
        conversationId: String,
    ): CollaborationSpaceConversationDto

    suspend fun open(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceOpenRequestDto,
    ): CollaborationReceiptDto
}
