package dev.fajar.starter.identity.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.DeviceSession

interface DeviceSessionRepository {
    suspend fun list(sessionId: String): AppResult<List<DeviceSession>>

    suspend fun revoke(sessionId: String, remoteId: String): AppResult<Unit>
}
