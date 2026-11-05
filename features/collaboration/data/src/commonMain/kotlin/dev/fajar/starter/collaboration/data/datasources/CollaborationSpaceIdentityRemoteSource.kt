package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationReceiptDto
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceIdentityResponseDto

interface CollaborationSpaceIdentityRemoteSource {
    suspend fun read(sessionId: String, spaceId: String): CollaborationSpaceIdentityResponseDto

    suspend fun select(
        sessionId: String,
        spaceId: String,
        operation: String,
    ): CollaborationReceiptDto
}
