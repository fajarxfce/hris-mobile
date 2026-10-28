package dev.fajar.starter.notifications.data.datasources

import kotlinx.coroutines.flow.Flow

interface PushTokenSource {
    suspend fun token(): String

    suspend fun deleteToken()

    fun observeTokens(): Flow<String>
}
