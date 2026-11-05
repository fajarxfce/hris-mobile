package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceIdentityApi
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceIdentityRemoteSource(private val api: CollaborationSpaceIdentityApi) :
    CollaborationSpaceIdentityRemoteSource {
    override suspend fun select(sessionId: String, spaceId: String, operation: String) =
        api.select(sessionId, spaceId, operation)
}
