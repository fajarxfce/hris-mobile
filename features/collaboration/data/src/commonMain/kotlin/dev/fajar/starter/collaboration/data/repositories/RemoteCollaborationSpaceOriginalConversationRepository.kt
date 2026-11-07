package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceOriginalConversationRemoteSource
import dev.fajar.starter.collaboration.data.dto.CollaborationOriginalConversationPositionDto
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceOriginalConversationRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceOriginalConversationRepository(
    private val remote: CollaborationSpaceOriginalConversationRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceOriginalConversationRepository {
    override suspend fun list(
        owner: CollaborationSpaceOwner,
        after: CollaborationOriginalConversationPosition?,
        limit: Int,
    ) = safeApiCall {
        validate(owner)
        require(validCollaborationOriginalConversationQuery(owner, after, limit))
        remote
            .list(
                owner.parent.sessionId,
                owner.spaceId,
                after?.let {
                    CollaborationOriginalConversationPositionDto(it.companyId, it.conversationId)
                },
                limit,
            )
            .toOriginalPage(owner, after, limit)
    }

    override suspend fun get(
        owner: CollaborationSpaceOwner,
        position: CollaborationOriginalConversationPosition,
    ) = safeApiCall {
        validate(owner)
        require(validCollaborationOriginalConversationPosition(position))
        remote
            .get(owner.parent.sessionId, owner.spaceId, position.companyId, position.conversationId)
            .toOriginalConversation(owner, position)
    }

    private fun validate(owner: CollaborationSpaceOwner) {
        val parent = owner.parent
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        require(validCollaborationOriginalConversationOwner(owner))
    }
}
