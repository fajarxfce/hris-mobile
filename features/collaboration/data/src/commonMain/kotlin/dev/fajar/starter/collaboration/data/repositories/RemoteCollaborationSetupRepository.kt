package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSetupRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSetupRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSetupRepository(
    private val remote: CollaborationSetupRemoteSource,
    private val server: IdentityServer,
) : CollaborationSetupRepository {
    override suspend fun read(owner: CollaborationOwner) = safeApiCall {
        validateOwner(owner)
        remote.read(owner.sessionId, owner.companyId).device?.toSetup()
    }

    override suspend fun register(owner: CollaborationOwner, command: CollaborationRegistration) =
        safeApiCall {
            validateOwner(owner)
            collaborationId(command.operationId)
            require(command.label == command.label.trim() && command.label.length in 1..64)
            require(command.label.none { it.code in 0..31 || it.code in 127..159 })
            remote
                .register(
                    owner.sessionId,
                    owner.companyId,
                    command.operationId,
                    RegisterCollaborationDeviceRequest(command.platform.name, command.label),
                )
                .toReceipt()
        }

    override suspend fun retry(owner: CollaborationOwner, command: CollaborationProvisioningRetry) =
        safeApiCall {
            validateOwner(owner)
            collaborationId(command.operationId)
            collaborationId(command.deviceId)
            require(
                command.deviceVersion in 0..999_999 && command.provisioningVersion in 0..999_998
            )
            remote
                .retry(
                    owner.sessionId,
                    owner.companyId,
                    command.operationId,
                    RetryCollaborationDeviceRequest(
                        command.deviceId,
                        command.deviceVersion,
                        command.provisioningVersion,
                    ),
                )
                .toReceipt()
        }

    override suspend fun bootstrap(owner: CollaborationOwner, command: CollaborationBootstrap) =
        safeApiCall {
            validateOwner(owner)
            collaborationId(command.operationId)
            collaborationId(command.deviceId)
            require(
                command.deviceVersion in 0..999_999 &&
                    (command.gatewayVersion?.let { it in 0..999_998 } != false)
            )
            remote
                .bootstrap(
                    owner.sessionId,
                    owner.companyId,
                    command.operationId,
                    BootstrapCollaborationDeviceRequest(
                        command.deviceId,
                        command.deviceVersion,
                        command.gatewayVersion,
                    ),
                )
                .toGateway(owner, command)
        }

    private fun validateOwner(owner: CollaborationOwner) {
        require(owner.audience == server.audience)
        require(owner.sessionId.length in 1..128 && owner.sessionId.none { it.code < 33 })
        collaborationId(owner.accountId)
        collaborationId(owner.companyId)
        collaborationId(owner.nativeSessionId)
    }
}
