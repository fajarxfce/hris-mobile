package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Exact provider facts through the installed original runtime; no creation, sync or send. */
interface CollaborationSpaceEventRepository {
    suspend fun findHead(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceEventHead>

    suspend fun read(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
        eventId: String,
    ): AppResult<CollaborationSpaceEventSnapshot>
}
