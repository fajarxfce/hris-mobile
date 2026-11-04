package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSpaceRemoteSource {
    suspend fun current(sessionId: String): CollaborationSpaceObservationDto

    suspend fun contacts(
        sessionId: String,
        spaceId: String,
        query: String,
        after: String?,
        limit: Int,
    ): CollaborationSpaceContactPageDto
}
