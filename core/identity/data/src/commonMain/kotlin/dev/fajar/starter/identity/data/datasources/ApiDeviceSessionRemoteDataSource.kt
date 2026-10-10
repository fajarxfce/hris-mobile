package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.api.DeviceSessionApi
import org.koin.core.annotation.Single

@Single
class ApiDeviceSessionRemoteDataSource(private val api: DeviceSessionApi) :
    DeviceSessionRemoteDataSource {
    override suspend fun list(sessionId: String) = api.list(sessionId)

    override suspend fun revoke(sessionId: String, remoteId: String) =
        api.revoke(sessionId, remoteId)
}
