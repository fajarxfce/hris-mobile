package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.NotificationAccessRepository

class EnableNotifications(private val access: NotificationAccessRepository) {
    suspend operator fun invoke(): AppResult<NotificationAccess> =
        when (val result = access.check()) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value == NotificationAccess.Denied) access.request() else result
        }
}
