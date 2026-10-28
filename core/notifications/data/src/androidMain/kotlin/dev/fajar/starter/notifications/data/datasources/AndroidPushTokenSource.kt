package dev.fajar.starter.notifications.data.datasources

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class AndroidPushTokenSource(private val context: Context) : PushTokenSource {
    private val changes = PushTokenChanges()
    private val tokenOperations = Mutex()

    // Keep token addressing compatible with Firebase Console and the iOS/Web adapters.
    // The optional FID registration mode requires a matching backend migration.
    @Suppress("DEPRECATION")
    override suspend fun token(): String =
        tokenOperations.withLock {
            if (FirebaseApp.getApps(context).isEmpty()) throw NotificationUnavailableException()
            FirebaseMessaging.getInstance().token.await()
        }

    override suspend fun deleteToken(): Unit =
        tokenOperations.withLock {
            if (FirebaseApp.getApps(context).isEmpty()) throw NotificationUnavailableException()
            FirebaseMessaging.getInstance().deleteToken().await()
            Unit
        }

    override fun observeTokens() = changes.observe(::token)

    @Suppress("UNUSED_PARAMETER")
    fun receivedToken(token: String) {
        changes.received()
    }
}
