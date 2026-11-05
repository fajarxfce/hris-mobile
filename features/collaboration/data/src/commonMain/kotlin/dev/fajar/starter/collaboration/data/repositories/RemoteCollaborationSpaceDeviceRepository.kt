package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceDeviceRemoteSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDeviceRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceDeviceRepository(
    private val remote: CollaborationSpaceDeviceRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceDeviceRepository {
    override suspend fun status(owner: CollaborationSpaceOwner) = safeApiCall {
        val parent = owner.parent
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        collaborationId(parent.accountId)
        collaborationId(parent.nativeSessionId)
        collaborationId(owner.spaceId)
        val response = remote.status(parent.sessionId, owner.spaceId)
        CollaborationSpaceDeviceObservation(owner, response.device?.toSpaceDeviceStatus())
    }
}
