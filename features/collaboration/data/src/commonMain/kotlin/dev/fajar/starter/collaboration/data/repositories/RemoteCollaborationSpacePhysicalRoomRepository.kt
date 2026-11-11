package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpacePhysicalRoomRemoteSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpacePhysicalRoomRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpacePhysicalRoomRepository(
    private val remote: CollaborationSpacePhysicalRoomRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpacePhysicalRoomRepository {
    override suspend fun published(
        owner: CollaborationSpaceOwner,
        serverName: String,
        roomId: String,
    ) = safeApiCall {
        require(owner.parent.audience == server.audience)
        require(validCollaborationOriginalConversationOwner(owner))
        require(validCollaborationSpaceRoomIdentity(serverName, roomId))
        remote
            .published(owner.parent.sessionId, owner.spaceId, roomId)
            .toSpacePhysicalRoomView(owner, serverName, roomId)
    }

    override suspend fun original(
        owner: CollaborationSpaceOwner,
        companyId: String,
        serverName: String,
        roomId: String,
    ) = safeApiCall {
        require(owner.parent.audience == server.audience)
        require(validCollaborationOriginalConversationOwner(owner))
        require(validCollaborationMessageId(companyId))
        require(validCollaborationSpaceRoomIdentity(serverName, roomId))
        remote
            .original(owner.parent.sessionId, owner.spaceId, companyId, roomId)
            .toOriginalConversation(owner)
            .also {
                require(it.conversation.position.companyId == companyId)
                val room = requireNotNull(it.room)
                require(room.serverName == serverName && room.roomId == roomId)
            }
    }
}
