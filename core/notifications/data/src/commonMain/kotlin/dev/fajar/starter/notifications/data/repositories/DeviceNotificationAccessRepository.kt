package dev.fajar.starter.notifications.data.repositories

import dev.fajar.starter.notifications.data.datasources.NotificationPermissionSource
import dev.fajar.starter.notifications.data.errors.safeNotificationCall
import dev.fajar.starter.notifications.data.mappers.toAccess
import dev.fajar.starter.notifications.domain.repositories.NotificationAccessRepository
import org.koin.core.annotation.Single

@Single
class DeviceNotificationAccessRepository(private val source: NotificationPermissionSource) :
    NotificationAccessRepository {
    override suspend fun check() = safeNotificationCall { source.check().toAccess() }

    override suspend fun request() = safeNotificationCall { source.request().toAccess() }
}
