package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceApi
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSpaceRemoteSource(private val api: CollaborationSpaceApi) :
    CollaborationSpaceRemoteSource {
    override suspend fun current(sessionId: String) = api.current(sessionId)

    override suspend fun contacts(
        sessionId: String,
        spaceId: String,
        query: String,
        after: String?,
        limit: Int,
    ) = api.contacts(sessionId, spaceId, query, after, limit)
}
