package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.repositories.NotificationAccessRepository
import dev.fajar.starter.notifications.domain.repositories.NotificationDeliveryRepository
import dev.fajar.starter.notifications.domain.repositories.NotificationRepository
import kotlin.time.Clock

class SendTestNotification(
    private val inbox: NotificationRepository,
    private val access: NotificationAccessRepository,
    private val delivery: NotificationDeliveryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        when (val result = access.check()) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (result.value != NotificationAccess.Granted)
                    return AppResult.Failed(
                        Failure(
                            FailureKind.Permission,
                            "Allow notifications before sending a test.",
                        )
                    )
        }
        val now = clock.now().toEpochMilliseconds()
        val message =
            NotificationMessage(
                "test-$now",
                "Test notification",
                "Notifications are working.",
                "inbox",
                now,
            )
        val saved = inbox.save(message)
        return if (saved is AppResult.Failed) saved else delivery.show(message)
    }
}
