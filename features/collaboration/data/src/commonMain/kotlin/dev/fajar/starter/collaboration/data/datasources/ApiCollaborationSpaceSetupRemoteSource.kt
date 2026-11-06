package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceSetupApi
import dev.fajar.starter.collaboration.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceSetupRemoteSource(private val api: CollaborationSpaceSetupApi) :
    CollaborationSpaceSetupRemoteSource {
    override suspend fun retry(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceRetryRequestDto,
    ) = api.retry(sessionId, spaceId, operation, input)

    override suspend fun bootstrap(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceBootstrapRequestDto,
    ) = api.bootstrap(sessionId, spaceId, operation, input)

    override suspend fun replaceBootstrap(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceBootstrapReplacementRequestDto,
    ) = api.replaceBootstrap(sessionId, spaceId, operation, input)
}
