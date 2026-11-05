package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDeviceResponseDto

interface CollaborationSpaceDeviceRemoteSource {
    suspend fun status(sessionId: String, spaceId: String): CollaborationSpaceDeviceResponseDto
}
