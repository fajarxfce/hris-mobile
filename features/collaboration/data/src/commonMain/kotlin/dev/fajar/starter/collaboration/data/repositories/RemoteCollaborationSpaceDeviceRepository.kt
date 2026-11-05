package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceDeviceRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRegistrationCommand
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDeviceRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceDeviceRepository(
    private val remote: CollaborationSpaceDeviceRemoteSource,
    private val server: IdentityServer,
) : CollaborationSpaceDeviceRepository {
    override suspend fun register(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceRegistrationCommand,
    ) = safeApiCall {
        validate(owner)
        require(validCollaborationSpaceRegistrationCommand(owner.localDirectoryAccess(), command))
        require(command.binding.nativeSessionId == owner.parent.nativeSessionId)
        val receipt =
            remote.register(
                owner.parent.sessionId,
                owner.spaceId,
                command.id,
                CollaborationSpaceRegistrationRequestDto(
                    command.platform.name,
                    command.label,
                    command.origin.name,
                    command.legacy?.let {
                        CollaborationSpaceLegacyPositionDto(it.binding.companyId, it.deviceId)
                    },
                    command.legacy?.identity?.fingerprint,
                ),
            )
        require(receipt.version in 0..999_999)
        receipt.toReceipt()
    }

    override suspend fun status(owner: CollaborationSpaceOwner) = safeApiCall {
        validate(owner)
        val response = remote.status(owner.parent.sessionId, owner.spaceId)
        CollaborationSpaceDeviceObservation(owner, response.device?.toSpaceDeviceStatus())
    }

    private fun validate(owner: CollaborationSpaceOwner) {
        val parent = owner.parent
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        collaborationId(parent.accountId)
        collaborationId(parent.nativeSessionId)
        collaborationId(owner.spaceId)
    }
}
