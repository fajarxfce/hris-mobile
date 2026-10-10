@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPayload
import kotlin.js.Promise
import kotlinx.coroutines.await

class BrowserNotificationDisplaySource : NotificationDisplaySource {
    override suspend fun show(payload: NotificationPayload) {
        showBrowserNotification(payload.id, payload.title, payload.body, payload.destination)
            .await<JsAny?>()
    }
}

@JsFun(
    """async (id, title, body, destination) => {
    const registration = await navigator.serviceWorker.register('./firebase-messaging-sw.js');
    await navigator.serviceWorker.ready;
    await registration.showNotification(title, {body, tag: id, data: {destination}});
}"""
)
private external fun showBrowserNotification(
    id: String,
    title: String,
    body: String,
    destination: String,
): Promise<JsAny?>
