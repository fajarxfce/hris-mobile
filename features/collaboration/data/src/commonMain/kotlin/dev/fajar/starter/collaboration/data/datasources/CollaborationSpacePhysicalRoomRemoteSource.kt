package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpacePhysicalRoomRemoteSource {
    suspend fun published(
        sessionId: String,
        spaceId: String,
        roomId: String,
    ): CollaborationSpaceRoomViewDto

    suspend fun original(
        sessionId: String,
        spaceId: String,
        companyId: String,
        roomId: String,
    ): CollaborationSpaceOriginalConversationDto
}
