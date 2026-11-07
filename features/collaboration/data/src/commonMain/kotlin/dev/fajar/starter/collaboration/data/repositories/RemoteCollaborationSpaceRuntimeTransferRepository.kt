package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceRuntimeTransferRemoteSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeTransferRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceRuntimeTransferRepository(
    private val remote: CollaborationSpaceRuntimeTransferRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceRuntimeTransferRepository {
    override suspend fun publication(
        owner: CollaborationSpaceOwner,
        transfer: CollaborationSpaceRuntimeTransfer,
    ) = safeApiCall {
        val input = transfer.toLegacyRuntimeRequest(owner, server)
        remote
            .publication(owner.parent.sessionId, owner.spaceId, input)
            .toLegacyPublication(transfer)
    }

    override suspend fun freeze(
        owner: CollaborationSpaceOwner,
        transfer: CollaborationSpaceRuntimeTransfer,
    ) = safeApiCall {
        val input = transfer.toLegacyRuntimeRequest(owner, server)
        val receipt =
            remote.freeze(owner.parent.sessionId, owner.spaceId, transfer.intent.operationId, input)
        require(receipt.id == transfer.legacy.deviceId && receipt.version == 1L)
        receipt.toReceipt()
    }

    override suspend fun drain(
        owner: CollaborationSpaceOwner,
        transfer: CollaborationSpaceRuntimeTransfer,
    ) = safeApiCall {
        val input = transfer.toLegacyRuntimeRequest(owner, server)
        remote.drain(owner.parent.sessionId, owner.spaceId, input).toRuntimeDrain(transfer)
    }
}
