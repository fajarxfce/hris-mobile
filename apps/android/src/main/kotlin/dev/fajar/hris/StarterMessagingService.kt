package dev.fajar.hris

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.notifications.data.datasources.AndroidPushTokenSource
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
        // HRIS sends data-only hints; provider display payloads are unsupported.
        if (message.notification != null) return
        runBlocking {
            (application as StarterApplication).container.koin.get<QueueInboxPushSignal>()(
                message.data
            )
        }
    }
}
