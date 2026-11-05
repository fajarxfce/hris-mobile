package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpaceSetupRemoteSource {
    suspend fun retry(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceRetryRequestDto,
    ): CollaborationReceiptDto

    suspend fun bootstrap(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceBootstrapRequestDto,
    ): CollaborationSpaceGatewayDto
}
