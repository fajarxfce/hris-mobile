@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPermission
import kotlin.js.Promise
import kotlinx.coroutines.await

class BrowserNotificationPermissionSource : NotificationPermissionSource {
    override suspend fun check() =
        when (browserPermission()) {
            "granted" -> NotificationPermission.Granted
            "unsupported" -> NotificationPermission.Unsupported
            else -> NotificationPermission.Denied
        }

    override suspend fun request() =
        when (requestBrowserPermission().await<JsString>().toString()) {
            "granted" -> NotificationPermission.Granted
            "unsupported" -> NotificationPermission.Unsupported
            else -> NotificationPermission.Denied
        }
}

@JsFun(
    "() => ('Notification' in window && 'serviceWorker' in navigator) ? Notification.permission : 'unsupported'"
)
private external fun browserPermission(): String

@JsFun(
    "() => ('Notification' in window && 'serviceWorker' in navigator) ? Notification.requestPermission() : Promise.resolve('unsupported')"
)
private external fun requestBrowserPermission(): Promise<JsString>
