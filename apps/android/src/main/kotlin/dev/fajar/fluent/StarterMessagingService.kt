package dev.fajar.fluent

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.fajar.starter.notifications.data.datasources.AndroidPushTokenSource
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.usecases.ReceiveNotification
import kotlinx.coroutines.runBlocking

/** Firebase invokes this worker-thread entry point; finish local work before returning. */
class StarterMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // Uses token addressing; see AndroidPushTokenSource.
    override fun onNewToken(token: String) {
        (application as StarterApplication)
            .container
            .koin
            .get<AndroidPushTokenSource>()
            .receivedToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification =
            NotificationMessage(
                id = message.messageId ?: message.data["id"].orEmpty(),
                title = message.notification?.title ?: message.data["title"].orEmpty(),
                body = message.notification?.body ?: message.data["body"].orEmpty(),
                destination = message.data["destination"] ?: "inbox",
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        runBlocking {
            (application as StarterApplication).container.koin.get<ReceiveNotification>()(
                notification
            )
        }
    }
}
