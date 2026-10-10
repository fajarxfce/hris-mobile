package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPayload
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.*

class AppleNotificationDisplaySource : NotificationDisplaySource {
    override suspend fun show(payload: NotificationPayload): Unit =
        suspendCancellableCoroutine { continuation ->
            val content =
                UNMutableNotificationContent().apply {
                    setTitle(payload.title)
                    setBody(payload.body)
                    setSound(UNNotificationSound.defaultSound())
                    setUserInfo(mapOf("id" to payload.id, "destination" to payload.destination))
                }
            val request = UNNotificationRequest.requestWithIdentifier(payload.id, content, null)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) {
                error ->
                if (continuation.isActive) {
                    if (error == null) continuation.resume(Unit)
                    else continuation.resumeWithException(AppleNotificationException(error))
                }
            }
        }
}
