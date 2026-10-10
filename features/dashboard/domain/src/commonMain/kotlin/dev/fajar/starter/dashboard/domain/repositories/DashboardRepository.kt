package dev.fajar.starter.dashboard.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.dashboard.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface DashboardRepository {
    fun observe(sessionId: String): Flow<AppResult<Dashboard?>>

    suspend fun loadNextPage(sessionId: String): AppResult<Unit>

    suspend fun refresh(sessionId: String): AppResult<Unit>

    suspend fun setSaved(sessionId: String, activityId: String, saved: Boolean): AppResult<Unit>

    suspend fun pendingChanges(sessionId: String, limit: Int): AppResult<List<ActivityChange>>

    suspend fun push(sessionId: String, change: ActivityChange): AppResult<Unit>

    suspend fun acknowledge(sessionId: String, operationId: String): AppResult<Unit>
}
