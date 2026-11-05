package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceDeviceApi
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceRegistrationRequestDto
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceDeviceRemoteSource(private val api: CollaborationSpaceDeviceApi) :
    CollaborationSpaceDeviceRemoteSource {
    override suspend fun register(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceRegistrationRequestDto,
    ) = api.register(sessionId, spaceId, operation, input)

    override suspend fun status(sessionId: String, spaceId: String) = api.status(sessionId, spaceId)
}
