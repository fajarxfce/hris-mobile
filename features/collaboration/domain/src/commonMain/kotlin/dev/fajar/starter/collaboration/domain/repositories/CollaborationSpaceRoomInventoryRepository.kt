package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Read local room metadata through the exact installed original client, without starting sync. */
interface CollaborationSpaceRoomInventoryRepository {
    suspend fun readInventory(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        afterRoomId: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceRoomInventoryPage>
}
