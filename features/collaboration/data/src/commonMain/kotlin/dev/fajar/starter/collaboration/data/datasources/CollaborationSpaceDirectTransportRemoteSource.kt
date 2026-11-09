package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDirectTransportDto

interface CollaborationSpaceDirectTransportRemoteSource {
    suspend fun read(
        sessionId: String,
        spaceId: String,
        conversationId: String,
    ): CollaborationSpaceDirectTransportDto
}
