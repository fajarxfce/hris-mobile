package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Finite history acquisition through the exact installed original client; no creation or send. */
interface CollaborationSpaceOriginalHistoryRepository {
    /** One provider head and its original SDK window. The use case owns live room admission. */
    suspend fun read(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalHistorySnapshot>
}
