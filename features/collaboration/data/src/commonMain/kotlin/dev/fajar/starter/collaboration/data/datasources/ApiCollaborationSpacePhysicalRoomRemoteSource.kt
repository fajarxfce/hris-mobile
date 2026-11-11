package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.*
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpacePhysicalRoomRemoteSource(
    private val rooms: CollaborationSpaceRoomApi,
    private val originals: CollaborationSpaceOriginalConversationApi,
) : CollaborationSpacePhysicalRoomRemoteSource {
    override suspend fun published(sessionId: String, spaceId: String, roomId: String) =
        rooms.readPhysical(sessionId, spaceId, roomId)

    override suspend fun original(
        sessionId: String,
        spaceId: String,
        companyId: String,
        roomId: String,
    ) = originals.readPhysical(sessionId, spaceId, companyId, roomId)
}
