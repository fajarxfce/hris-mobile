package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceConversationRemoteSource
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceOpenRequestDto
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceConversationRepository(
    private val remote: CollaborationSpaceConversationRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceConversationRepository {
    override suspend fun list(owner: CollaborationSpaceOwner, after: String?, limit: Int) =
        safeApiCall {
            validate(owner)
            require(validCollaborationSpaceConversationQuery(owner, after, limit))
            remote
                .list(owner.parent.sessionId, owner.spaceId, after, limit)
                .toSpaceConversationPage(owner, after, limit)
        }

    override suspend fun get(owner: CollaborationSpaceOwner, conversationId: String) = safeApiCall {
        validate(owner)
        require(validCollaborationSpaceConversationId(conversationId))
        remote
            .get(owner.parent.sessionId, owner.spaceId, conversationId)
            .toSpaceConversation(owner, conversationId)
    }

    override suspend fun open(
        owner: CollaborationSpaceOwner,
        operation: String,
        peerAccountId: String,
    ) = safeApiCall {
        validate(owner)
        require(validCollaborationSpaceConversationId(operation))
        require(validCollaborationSpaceConversationId(peerAccountId))
        require(peerAccountId != owner.parent.accountId)
        remote
            .open(
                owner.parent.sessionId,
                owner.spaceId,
                operation,
                CollaborationSpaceOpenRequestDto(peerAccountId),
            )
            .toReceipt()
            .also { require(validCollaborationSpaceConversationId(it.id) && it.version == 0L) }
    }

    private fun validate(owner: CollaborationSpaceOwner) {
        require(owner.parent.audience == server.audience)
        require(
            owner.parent.sessionId.length in 1..128 &&
                owner.parent.sessionId.all { it.code in 33..126 }
        )
        require(validCollaborationSpaceConversationQuery(owner, null, 1))
    }
}
