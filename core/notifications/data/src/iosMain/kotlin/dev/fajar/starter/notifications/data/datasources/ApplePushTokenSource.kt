package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApplePushTokenSource(private val client: AppleFirebaseClient) : PushTokenSource {
    private val changes = PushTokenChanges()
    private val tokenOperations = Mutex()

    override suspend fun token(): String =
        tokenOperations.withLock {
            if (!client.configured) throw NotificationUnavailableException()
            suspendCancellableCoroutine { continuation ->
                client.fetchToken { value, error ->
                    if (continuation.isActive) {
                        if (value != null) continuation.resume(value)
                        else
                            continuation.resumeWithException(
                                error?.let(::AppleNotificationException)
                                    ?: IllegalStateException("Firebase returned no token.")
                            )
                    }
                }
            }
        }

    override suspend fun deleteToken(): Unit =
        tokenOperations.withLock {
            if (!client.configured) throw NotificationUnavailableException()
            suspendCancellableCoroutine { continuation ->
                client.deleteToken { error ->
                    if (continuation.isActive) {
                        if (error == null) continuation.resume(Unit)
                        else continuation.resumeWithException(AppleNotificationException(error))
                    }
                }
            }
        }

    override fun observeTokens() = changes.observe(::token)

    @Suppress("UNUSED_PARAMETER")
    fun receivedToken(token: String) {
        changes.received()
    }
}
