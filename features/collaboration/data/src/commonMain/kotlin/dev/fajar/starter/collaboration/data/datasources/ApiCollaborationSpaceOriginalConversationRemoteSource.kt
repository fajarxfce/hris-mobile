package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceOriginalConversationApi
import dev.fajar.starter.collaboration.data.dto.CollaborationOriginalConversationPositionDto
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceOriginalConversationRemoteSource(
    private val api: CollaborationSpaceOriginalConversationApi
) : CollaborationSpaceOriginalConversationRemoteSource {
    override suspend fun list(
        sessionId: String,
        spaceId: String,
        after: CollaborationOriginalConversationPositionDto?,
        limit: Int,
    ) = api.list(sessionId, spaceId, after, limit)

    override suspend fun get(
        sessionId: String,
        spaceId: String,
        companyId: String,
        conversationId: String,
    ) = api.get(sessionId, spaceId, companyId, conversationId)
}
