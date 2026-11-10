package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/**
 * One bounded backward metadata page through the installed original runtime. Positions belong to
 * that physical device and room. No client, sync, timeline, send or durable progress is created.
 * Current content admission and atomic reference retention belong to the acquiring use case.
 */
interface CollaborationSpaceProviderHistoryRepository {
    suspend fun readPage(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
        position: String?,
        untilPosition: String? = null,
    ): AppResult<CollaborationSpaceProviderHistoryPage>
}
