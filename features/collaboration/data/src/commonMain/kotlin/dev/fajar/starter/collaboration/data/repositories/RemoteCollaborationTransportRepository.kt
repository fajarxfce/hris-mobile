package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationTransportRemoteSource
import dev.fajar.starter.collaboration.data.mappers.collaborationId
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTransportRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationTransportRepository(
    private val remote: CollaborationTransportRemoteSource,
    private val server: IdentityServer,
) : CollaborationTransportRepository {
    override suspend fun read(owner: CollaborationOwner, conversationId: String) = safeApiCall {
        require(owner.audience == server.audience)
        require(owner.sessionId.length in 1..128 && owner.sessionId.none { it.code < 33 })
        collaborationId(owner.accountId)
        collaborationId(owner.companyId)
        collaborationId(owner.nativeSessionId)
        collaborationId(conversationId)
        val dto = remote.transport(owner.sessionId, owner.companyId, conversationId)
        require(dto.companyId == owner.companyId && dto.accountId == owner.accountId)
        require(
            dto.nativeSessionId == owner.nativeSessionId && dto.conversationId == conversationId
        )
        collaborationId(dto.deviceId)
        collaborationId(dto.firstAccountId)
        collaborationId(dto.secondAccountId)
        require(dto.firstAccountId != dto.secondAccountId)
        require(owner.accountId in setOf(dto.firstAccountId, dto.secondAccountId))
        require(dto.deviceVersion >= 0 && dto.roomProvisioningVersion in 0..999_999)
        require(dto.matrixRoomId.length in 2..255 && dto.matrixRoomId.startsWith('!'))
        require(dto.matrixRoomId.all { it.code in 33..126 })
        CollaborationDirectTransport(
            owner,
            dto.deviceId,
            dto.deviceVersion,
            CollaborationNativePlatform.valueOf(dto.platform),
            dto.conversationId,
            dto.firstAccountId,
            dto.secondAccountId,
            dto.matrixRoomId,
            dto.roomProvisioningVersion,
            dto.recipientReady,
        )
    }
}
