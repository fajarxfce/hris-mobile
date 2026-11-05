package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationReceiptDto
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDeviceResponseDto
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceRegistrationRequestDto

interface CollaborationSpaceDeviceRemoteSource {
    suspend fun register(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceRegistrationRequestDto,
    ): CollaborationReceiptDto

    suspend fun status(sessionId: String, spaceId: String): CollaborationSpaceDeviceResponseDto
}
