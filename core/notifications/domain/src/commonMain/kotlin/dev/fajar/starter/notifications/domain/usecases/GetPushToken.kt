package dev.fajar.starter.notifications.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.*

/** Access is explicit; token acquisition must never prompt for permission. */
class GetPushToken(
    private val access: NotificationAccessRepository,
    private val repository: PushTokenRepository,
) {
    suspend operator fun invoke(): AppResult<String> =
        when (val result = access.check()) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                when (result.value) {
                    NotificationAccess.Granted -> repository.token()
                    NotificationAccess.Denied ->
                        AppResult.Failed(
                            Failure(
                                FailureKind.Permission,
                                "Allow notifications before registering push.",
                            )
                        )
                    NotificationAccess.Unavailable ->
                        AppResult.Failed(
                            Failure(
                                FailureKind.Unavailable,
                                "Push notifications are unavailable on this device.",
                            )
                        )
                }
        }
}
