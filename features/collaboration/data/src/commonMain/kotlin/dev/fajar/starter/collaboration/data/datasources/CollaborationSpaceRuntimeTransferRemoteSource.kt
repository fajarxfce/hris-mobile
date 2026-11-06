package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpaceRuntimeTransferRemoteSource {
    suspend fun freeze(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ): CollaborationReceiptDto

    suspend fun drain(
        sessionId: String,
        spaceId: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ): CollaborationLegacyRuntimeDrainDto
}
