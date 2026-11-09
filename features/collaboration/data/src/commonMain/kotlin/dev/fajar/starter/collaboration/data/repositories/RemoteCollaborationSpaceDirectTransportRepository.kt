package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceDirectTransportRemoteSource
import dev.fajar.starter.collaboration.data.mappers.toSpaceDirectTransport
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceConversation
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceConversation
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectTransportRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceDirectTransportRepository(
    private val remote: CollaborationSpaceDirectTransportRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceDirectTransportRepository {
    override suspend fun read(conversation: CollaborationSpaceConversation) = safeApiCall {
        val owner = conversation.owner
        val parent = owner.parent
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        require(validCollaborationSpaceConversation(conversation))
        remote
            .read(parent.sessionId, owner.spaceId, conversation.id)
            .toSpaceDirectTransport(conversation)
    }
}
