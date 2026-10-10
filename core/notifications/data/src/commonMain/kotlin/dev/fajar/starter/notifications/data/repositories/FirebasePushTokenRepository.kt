package dev.fajar.starter.notifications.data.repositories

import dev.fajar.starter.notifications.data.datasources.PushTokenSource
import dev.fajar.starter.notifications.data.errors.safeNotificationCall
import dev.fajar.starter.notifications.data.errors.safeNotificationFlow
import dev.fajar.starter.notifications.domain.repositories.PushTokenRepository
import org.koin.core.annotation.Single

@Single
class FirebasePushTokenRepository(private val source: PushTokenSource) : PushTokenRepository {
    override suspend fun token() = safeNotificationCall { source.token() }

    override fun observeTokens() = safeNotificationFlow(source.observeTokens())
}
