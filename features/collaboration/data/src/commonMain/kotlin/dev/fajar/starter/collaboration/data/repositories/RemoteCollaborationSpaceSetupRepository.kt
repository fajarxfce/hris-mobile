package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceSetupRemoteSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSetupRepository
import dev.fajar.starter.common.config.AppBuild
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
        val receipt =
            remote.retry(
                owner.parent.sessionId,
                owner.spaceId,
                command.operationId,
                command.toSpaceRetryRequest(owner, server),
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
        remote
            .bootstrap(
                owner.parent.sessionId,
                owner.spaceId,
                command.operationId,
                command.toSpaceBootstrapRequest(owner, server, build),
            )
            .toSpaceGateway(command)
    }

    override suspend fun replaceBootstrap(
        owner: CollaborationSpaceOwner,
        command: CollaborationSpaceBootstrapReplacement,
    ) = safeApiCall {
        val request = command.toSpaceReplacementRequest(owner, server, build)
        remote
            .replaceBootstrap(
                owner.parent.sessionId,
                owner.spaceId,
                command.replacement.operationId,
                request,
            )
            .toSpaceBootstrapReplacement(command, request.supersedes)
    }
}
