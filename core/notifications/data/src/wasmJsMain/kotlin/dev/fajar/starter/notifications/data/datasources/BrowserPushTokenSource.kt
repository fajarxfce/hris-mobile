@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.dto.NotificationPayload
import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class BrowserPushTokenSource : PushTokenSource {
    override suspend fun token(): String {
        if (!browserFirebaseConfigured()) throw NotificationUnavailableException()
        return browserPushToken().await<JsString>().toString()
    }

    override fun observeTokens() = flow { emit(token()) }

    fun messages() = callbackFlow {
        if (!browserFirebaseConfigured()) {
            close()
            return@callbackFlow
        }
        val registration = listenBrowserPush { encoded ->
            try {
                trySend(Json.decodeFromString<NotificationPayload>(encoded))
            } catch (error: Exception) {
                close(error)
            }
        }
        val listener =
            try {
                registration.await<JsAny>()
            } catch (cancelled: CancellationException) {
                cancelPendingBrowserPush(registration)
                throw cancelled
            }
        awaitClose { stopBrowserPush(listener) }
    }
}

@JsFun("() => Boolean(self.FLUENT_FIREBASE?.firebase && self.FLUENT_FIREBASE?.vapidKey)")
private external fun browserFirebaseConfigured(): Boolean

@JsFun(
    """async () => {
    if (Notification.permission !== 'granted') throw new Error('Notification access is required.');
    const app = await import('firebase/app');
    const messaging = await import('firebase/messaging');
    const firebaseApp = app.getApps()[0] || app.initializeApp(self.FLUENT_FIREBASE.firebase);
    const registration = await navigator.serviceWorker.register('./firebase-messaging-sw.js');
    await navigator.serviceWorker.ready;
    return await messaging.getToken(messaging.getMessaging(firebaseApp), {
        vapidKey: self.FLUENT_FIREBASE.vapidKey, serviceWorkerRegistration: registration
    });
}"""
)
private external fun browserPushToken(): Promise<JsString>

@JsFun(
    """async (handler) => {
    const app = await import('firebase/app');
    const messaging = await import('firebase/messaging');
    const firebaseApp = app.getApps()[0] || app.initializeApp(self.FLUENT_FIREBASE.firebase);
    return messaging.onMessage(messaging.getMessaging(firebaseApp), (payload) => {
        const data = payload.data || {}, notification = payload.notification || {};
        handler(JSON.stringify({id: payload.messageId || data.id || '', title: notification.title || data.title || '',
            body: notification.body || data.body || '', destination: data.destination || 'inbox'}));
    });
}"""
)
private external fun listenBrowserPush(handler: (String) -> Unit): Promise<JsAny>

@JsFun("(unsubscribe) => unsubscribe()") private external fun stopBrowserPush(listener: JsAny)

@JsFun("(pending) => { pending.then(unsubscribe => unsubscribe(), () => {}); }")
private external fun cancelPendingBrowserPush(pending: Promise<JsAny>)
