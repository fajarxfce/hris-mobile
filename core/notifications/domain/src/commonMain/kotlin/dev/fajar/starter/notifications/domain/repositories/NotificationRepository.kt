package dev.fajar.starter.notifications.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import kotlinx.coroutines.flow.Flow

interface NotificationRepository {
    fun observe(): Flow<AppResult<List<NotificationMessage>>>

    suspend fun save(message: NotificationMessage): AppResult<Unit>

    suspend fun markRead(id: String): AppResult<Unit>

    suspend fun clear(): AppResult<Unit>
}
