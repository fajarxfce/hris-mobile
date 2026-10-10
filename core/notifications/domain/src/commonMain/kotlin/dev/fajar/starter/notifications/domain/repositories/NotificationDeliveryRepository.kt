package dev.fajar.starter.notifications.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.domain.entities.NotificationMessage

interface NotificationDeliveryRepository {
    suspend fun show(message: NotificationMessage): AppResult<Unit>
}
