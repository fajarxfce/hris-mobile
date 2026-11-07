package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceRoomApi
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceRoomRequestDto
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceRoomRemoteSource(private val api: CollaborationSpaceRoomApi) :
    CollaborationSpaceRoomRemoteSource {
    override suspend fun get(sessionId: String, spaceId: String, conversationId: String) =
        api.get(sessionId, spaceId, conversationId)

    override suspend fun request(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        operation: String,
        input: CollaborationSpaceRoomRequestDto,
    ) = api.request(sessionId, spaceId, conversationId, operation, input)
}
