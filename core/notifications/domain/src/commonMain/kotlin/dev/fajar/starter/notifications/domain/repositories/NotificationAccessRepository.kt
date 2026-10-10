package dev.fajar.starter.notifications.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.domain.entities.NotificationAccess

interface NotificationAccessRepository {
    suspend fun check(): AppResult<NotificationAccess>

    suspend fun request(): AppResult<NotificationAccess>
}
