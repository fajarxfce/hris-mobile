package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceDeviceApi
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceDeviceRemoteSource(private val api: CollaborationSpaceDeviceApi) :
    CollaborationSpaceDeviceRemoteSource {
    override suspend fun status(sessionId: String, spaceId: String) = api.status(sessionId, spaceId)
}
