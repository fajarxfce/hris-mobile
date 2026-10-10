package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.repositories.NotificationAccessRepository
import dev.fajar.starter.notifications.domain.repositories.NotificationDeliveryRepository
import dev.fajar.starter.notifications.domain.repositories.NotificationRepository

/** Receiving a message never requests permission. The inbox survives denied OS delivery. */
class ReceiveNotification(
    private val inbox: NotificationRepository,
    private val access: NotificationAccessRepository,
    private val delivery: NotificationDeliveryRepository,
) {
    suspend operator fun invoke(
        message: NotificationMessage,
        systemDisplayed: Boolean = false,
    ): AppResult<Unit> {
        if (message.id.isBlank() || message.title.isBlank())
            return AppResult.Failed(
                Failure(FailureKind.Validation, "The notification is incomplete.")
            )
        val saved = inbox.save(message)
        if (saved is AppResult.Failed || systemDisplayed) return saved
        return when (val permission = access.check()) {
            is AppResult.Failed -> permission
            is AppResult.Success ->
                if (permission.value == NotificationAccess.Granted) delivery.show(message)
                else saved
        }
    }
}
