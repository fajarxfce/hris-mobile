package dev.fajar.starter.notifications.domain.repositories

import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

interface PushTokenRepository {
    suspend fun token(): AppResult<String>

    /** Retire the provider token before another account/session can register this installation. */
    suspend fun deleteToken(): AppResult<Unit>

    fun observeTokens(): Flow<AppResult<String>>
}
