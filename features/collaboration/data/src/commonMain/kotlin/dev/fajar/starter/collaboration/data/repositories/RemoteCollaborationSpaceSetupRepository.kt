package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceSetupRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSetupRepository
import dev.fajar.starter.common.config.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSpaceSetupRepository(
    private val remote: CollaborationSpaceSetupRemoteSource,
    private val server: IdentityServer,
    private val build: AppBuild,
) : CollaborationSpaceSetupRepository {
    override suspend fun retry(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceProvisioningRetry,
    ) = safeApiCall {
        validate(owner, command.binding, command.operationId, command.deviceId)
        require(command.deviceVersion in 0..999_998 && command.provisioningRevision in 0..999_998)
        val receipt =
            remote.retry(
                owner.parent.sessionId,
                owner.spaceId,
                command.operationId,
                CollaborationSpaceRetryRequestDto(
                    command.deviceId,
                    command.deviceVersion,
                    command.provisioningRevision,
                ),
            )
        require(
            receipt.id == command.deviceId && receipt.version == command.provisioningRevision + 1
        )
        receipt.toReceipt()
    }

    override suspend fun bootstrap(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceBootstrap,
    ) = safeApiCall {
        validate(owner, command.binding, command.operationId, command.deviceId)
        require(command.deviceVersion in 0..999_999)
        require(command.gatewayVersion?.let { it in 0..999_998 } != false)
        // Wire integrity: the retained operation binds the actual default client headers.
        // Never forge old headers to replay an operation prepared by another build/platform.
        require(command.clientBuild in 1..999_999_999L && command.clientBuild == build.number)
        require(
            (command.platform == CollaborationNativePlatform.ANDROID &&
                build.platform == AppPlatform.Android) ||
                (command.platform == CollaborationNativePlatform.IOS &&
                    build.platform == AppPlatform.Ios)
        )
        val provider = command.provider
        require(
            CollaborationSpaceIdentityDto(
                    provider.spaceId,
                    provider.accountId,
                    provider.serverName,
                    provider.matrixUserId,
                    provider.origin.name,
                    provider.legacyCompanyId,
                    provider.version,
                    provider.createdAt.toString(),
                )
                .toSpaceIdentity(owner) == provider
        )
        require(
            command.matrixDeviceId == "HRISG_${command.deviceId.replace("-", "")}" ||
                (provider.origin == CollaborationSpaceIdentityOrigin.LEGACY &&
                    command.matrixDeviceId.matches(Regex("HRIS_[a-f0-9]{32}")))
        )
        remote
            .bootstrap(
                owner.parent.sessionId,
                owner.spaceId,
                command.operationId,
                CollaborationSpaceBootstrapRequestDto(
                    command.deviceId,
                    command.deviceVersion,
                    command.gatewayVersion,
                ),
            )
            .toSpaceGateway(command)
    }

    private fun validate(
        owner: CollaborationSpaceOwner,
        binding: CollaborationSpaceDeviceBinding,
        operation: String,
        device: String,
    ) {
        val parent = owner.parent
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        listOf(parent.accountId, parent.nativeSessionId, owner.spaceId, operation, device)
            .forEach(::collaborationId)
        require(
            binding ==
                CollaborationSpaceDeviceBinding(
                    parent.audience,
                    parent.accountId,
                    owner.spaceId,
                    parent.nativeSessionId,
                )
        )
    }
}
