package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

class ApplePushTokenSource(private val client: AppleFirebaseClient) : PushTokenSource {
    private val tokens =
        MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun token(): String {
        if (!client.configured) throw NotificationUnavailableException()
        return suspendCancellableCoroutine { continuation ->
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

    override fun observeTokens() = flow {
        emit(token())
        emitAll(tokens)
    }

    fun receivedToken(token: String) {
        tokens.tryEmit(token)
    }
}
