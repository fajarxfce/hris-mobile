package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceIdentityRemoteSource
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIdentityCommand
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIdentityRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceIdentityRepository(
    private val remote: CollaborationSpaceIdentityRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceIdentityRepository {
    override suspend fun select(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceIdentityCommand,
    ) = safeApiCall {
        require(owner.parent.audience == server.audience)
        require(validCollaborationSpaceIdentityCommand(owner.localDirectoryAccess(), command))
        require(validCollaborationMessageId(owner.parent.nativeSessionId))
        val receipt = remote.select(owner.parent.sessionId, owner.spaceId, command.id)
        require(receipt.id == owner.parent.accountId && receipt.version == 0L)
        CollaborationSetupReceipt(receipt.id, receipt.version)
    }
}
