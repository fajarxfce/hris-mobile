package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceRuntimeTransferApi
import dev.fajar.starter.collaboration.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceRuntimeTransferRemoteSource(
    private val api: CollaborationSpaceRuntimeTransferApi
) : CollaborationSpaceRuntimeTransferRemoteSource {
    override suspend fun freeze(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ) = api.freeze(sessionId, spaceId, operation, input)

    override suspend fun drain(
        sessionId: String,
        spaceId: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ) = api.drain(sessionId, spaceId, input)
}
