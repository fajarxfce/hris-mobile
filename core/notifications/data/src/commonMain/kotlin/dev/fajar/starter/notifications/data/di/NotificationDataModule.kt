package dev.fajar.starter.notifications.data.di

import dev.fajar.starter.notifications.domain.repositories.*
import dev.fajar.starter.notifications.domain.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.notifications.data")
class NotificationDataModule {
    @Factory
    fun check(repository: NotificationAccessRepository) = CheckNotificationAccess(repository)

    @Factory
    fun test(
        inbox: NotificationRepository,
        access: NotificationAccessRepository,
        delivery: NotificationDeliveryRepository,
    ) = SendTestNotification(inbox, access, delivery, kotlin.time.Clock.System)

    @Factory fun observe(repository: NotificationRepository) = ObserveNotifications(repository)

    @Factory fun markRead(repository: NotificationRepository) = MarkNotificationRead(repository)

    @Factory fun clear(repository: NotificationRepository) = ClearNotifications(repository)

    @Factory fun enable(repository: NotificationAccessRepository) = EnableNotifications(repository)

    @Factory
    fun pushToken(access: NotificationAccessRepository, repository: PushTokenRepository) =
        GetPushToken(access, repository)

    @Factory
    fun receive(
        inbox: NotificationRepository,
        access: NotificationAccessRepository,
        delivery: NotificationDeliveryRepository,
    ) = ReceiveNotification(inbox, access, delivery)
}
