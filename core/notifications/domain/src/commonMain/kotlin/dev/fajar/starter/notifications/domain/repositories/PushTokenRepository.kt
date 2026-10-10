package dev.fajar.starter.notifications.domain.repositories

import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

interface PushTokenRepository {
    suspend fun token(): AppResult<String>

    fun observeTokens(): Flow<AppResult<String>>
}
