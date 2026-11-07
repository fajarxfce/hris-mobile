package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceConversationApi
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceOpenRequestDto
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceConversationRemoteSource(
    private val api: CollaborationSpaceConversationApi
) : CollaborationSpaceConversationRemoteSource {
    override suspend fun list(sessionId: String, spaceId: String, after: String?, limit: Int) =
        api.list(sessionId, spaceId, after, limit)

    override suspend fun get(sessionId: String, spaceId: String, conversationId: String) =
        api.get(sessionId, spaceId, conversationId)

    override suspend fun open(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceOpenRequestDto,
    ) = api.open(sessionId, spaceId, operation, input)
}
