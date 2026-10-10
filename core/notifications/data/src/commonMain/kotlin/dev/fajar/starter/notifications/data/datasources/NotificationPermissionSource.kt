package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPermission

interface NotificationPermissionSource {
    suspend fun check(): NotificationPermission

    suspend fun request(): NotificationPermission
}
