package dev.fajar.starter.notifications.data.datasources

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

class AndroidPushTokenSource(private val context: Context) : PushTokenSource {
    private val tokens =
        MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Keep token addressing compatible with Firebase Console and the iOS/Web adapters.
    // The optional FID registration mode requires a matching backend migration.
    @Suppress("DEPRECATION")
    override suspend fun token(): String {
        if (FirebaseApp.getApps(context).isEmpty()) throw NotificationUnavailableException()
        return FirebaseMessaging.getInstance().token.await()
    }

    override fun observeTokens() = flow {
        emit(token())
        emitAll(tokens)
    }

    fun receivedToken(token: String) {
        tokens.tryEmit(token)
    }
}
