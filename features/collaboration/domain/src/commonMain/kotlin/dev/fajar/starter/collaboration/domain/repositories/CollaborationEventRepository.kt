package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** A finite read through the current installed client. The usecase owns live room admission. */
interface CollaborationEventRepository {
    suspend fun read(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        eventId: String,
    ): AppResult<CollaborationEventSnapshot>
}
