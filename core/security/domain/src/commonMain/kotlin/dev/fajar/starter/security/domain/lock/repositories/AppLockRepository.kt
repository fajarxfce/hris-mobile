package dev.fajar.starter.security.domain.lock.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.security.domain.lock.entities.*
import kotlinx.coroutines.flow.Flow

interface AppLockRepository {
    fun observe(): Flow<AppResult<AppLockSnapshot>>

    suspend fun current(): AppResult<AppLockSnapshot>

    suspend fun setEnabled(enabled: Boolean): AppResult<Unit>

    suspend fun authorize(grant: DeviceAuthorization): AppResult<Unit>

    suspend fun renew(expected: DeviceAuthorization, expiresAtMillis: Long): AppResult<Boolean>

    suspend fun revoke(): AppResult<Unit>
}
