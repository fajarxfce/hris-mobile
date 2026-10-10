package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.DeviceSessionDto

interface DeviceSessionRemoteDataSource {
    suspend fun list(sessionId: String): List<DeviceSessionDto>

    suspend fun revoke(sessionId: String, remoteId: String)
}
