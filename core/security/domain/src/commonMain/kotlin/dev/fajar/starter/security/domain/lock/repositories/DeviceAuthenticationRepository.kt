package dev.fajar.starter.security.domain.lock.repositories

import dev.fajar.starter.common.result.AppResult

interface DeviceAuthenticationRepository {
    suspend fun available(): AppResult<Boolean>

    suspend fun authenticate(): AppResult<Unit>
}
