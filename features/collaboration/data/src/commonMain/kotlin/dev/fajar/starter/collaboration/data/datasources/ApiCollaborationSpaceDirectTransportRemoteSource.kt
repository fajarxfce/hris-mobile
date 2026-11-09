package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceDirectTransportApi
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceDirectTransportRemoteSource(
    private val api: CollaborationSpaceDirectTransportApi
) : CollaborationSpaceDirectTransportRemoteSource {
    override suspend fun read(sessionId: String, spaceId: String, conversationId: String) =
        api.read(sessionId, spaceId, conversationId)
}
