package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationReceiptDto

interface CollaborationSpaceIdentityRemoteSource {
    suspend fun select(
        sessionId: String,
        spaceId: String,
        operation: String,
    ): CollaborationReceiptDto
}
