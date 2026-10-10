package dev.fajar.starter.notifications.data.datasources

import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class UnavailablePushTokenSource : PushTokenSource {
    override suspend fun token(): String = throw NotificationUnavailableException()

    override fun observeTokens(): Flow<String> = flow { throw NotificationUnavailableException() }
}
