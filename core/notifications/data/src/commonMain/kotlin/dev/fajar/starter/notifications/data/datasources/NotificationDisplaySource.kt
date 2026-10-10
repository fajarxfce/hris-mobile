package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPayload

interface NotificationDisplaySource {
    suspend fun show(payload: NotificationPayload)
}
