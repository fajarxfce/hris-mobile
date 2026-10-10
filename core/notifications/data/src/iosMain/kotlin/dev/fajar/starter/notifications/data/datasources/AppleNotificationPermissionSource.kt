package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPermission
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.UserNotifications.*

class AppleNotificationPermissionSource : NotificationPermissionSource {
    override suspend fun check(): NotificationPermission =
        suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter()
                .getNotificationSettingsWithCompletionHandler { settings ->
                    if (continuation.isActive)
                        continuation.resume(
                            if (
                                settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
                                    settings?.authorizationStatus ==
                                        UNAuthorizationStatusProvisional
                            )
                                NotificationPermission.Granted
                            else NotificationPermission.Denied
                        )
                }
        }

    override suspend fun request(): NotificationPermission =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or
                            UNAuthorizationOptionBadge or
                            UNAuthorizationOptionSound
                    ) { granted, error ->
                        if (continuation.isActive) {
                            if (error != null)
                                continuation.resumeWithException(AppleNotificationException(error))
                            else
                                continuation.resume(
                                    if (granted) NotificationPermission.Granted
                                    else NotificationPermission.Denied
                                )
                        }
                    }
            }
        }
}
