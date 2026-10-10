package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.identity.data.datasources.DeviceSessionRemoteDataSource
import dev.fajar.starter.identity.data.mappers.toDeviceSessions
import dev.fajar.starter.identity.domain.repositories.DeviceSessionRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteDeviceSessionRepository(private val remote: DeviceSessionRemoteDataSource) :
    DeviceSessionRepository {
    override suspend fun list(sessionId: String) = safeApiCall {
        remote.list(sessionId).toDeviceSessions()
    }

    override suspend fun revoke(sessionId: String, remoteId: String) = safeApiCall {
        remote.revoke(sessionId, remoteId)
    }
}
