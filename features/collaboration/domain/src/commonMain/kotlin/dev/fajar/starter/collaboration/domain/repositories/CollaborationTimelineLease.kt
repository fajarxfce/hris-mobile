package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** A child observation, not the parent runtime or a renewable authorization credential. */
interface CollaborationTimelineLease {
    val owner: CollaborationOwner
    val deviceId: String
    val matrixRoomId: String
    val updates: Flow<AppResult<List<CollaborationTimelineItem>>>

    /** Releases this observer only; pending native cleanup stays owned by the parent. */
    suspend fun close(): AppResult<Unit>
}
