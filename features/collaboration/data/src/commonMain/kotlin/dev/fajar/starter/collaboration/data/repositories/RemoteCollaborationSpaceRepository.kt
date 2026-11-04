package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceRemoteSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceContactRequest
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceRepository(
    private val remote: CollaborationSpaceRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceRepository {
    override suspend fun current(parent: CollaborationNativeParent) = safeApiCall {
        validate(parent)
        remote.current(parent.sessionId).toObservation(parent)
    }

    override suspend fun contacts(
        owner: CollaborationSpaceOwner,
        query: String,
        after: String?,
        limit: Int,
    ) = safeApiCall {
        validate(owner.parent)
        require(validCollaborationSpaceContactRequest(owner, query, after, limit))
        remote
            .contacts(owner.parent.sessionId, owner.spaceId, query.trim(), after, limit)
            .toPage(owner, after, limit)
    }

    private fun validate(parent: CollaborationNativeParent) {
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        collaborationId(parent.accountId)
        collaborationId(parent.nativeSessionId)
    }
}
