package dev.fajar.starter.notifications.data.repositories

import dev.fajar.starter.notifications.data.datasources.NotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.NotificationPayload
import dev.fajar.starter.notifications.data.errors.safeNotificationCall
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.repositories.NotificationDeliveryRepository
import org.koin.core.annotation.Single

@Single
class DeviceNotificationDeliveryRepository(private val source: NotificationDisplaySource) :
    NotificationDeliveryRepository {
    override suspend fun show(message: NotificationMessage) = safeNotificationCall {
        source.show(
            NotificationPayload(message.id, message.title, message.body, message.destination)
        )
    }
}
