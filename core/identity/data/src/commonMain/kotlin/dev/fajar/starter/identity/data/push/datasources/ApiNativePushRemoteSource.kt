package dev.fajar.starter.identity.data.push.datasources

import dev.fajar.starter.identity.data.push.api.NativePushApi
import dev.fajar.starter.identity.data.push.dto.*
import org.koin.core.annotation.Single

@Single
class ApiNativePushRemoteSource(private val api: NativePushApi) : NativePushRemoteSource {
    override suspend fun read(sessionId: String) = api.read(sessionId)

    override suspend fun save(
        sessionId: String,
        operationId: String,
        input: SaveNativePushRequest,
    ) = api.save(sessionId, operationId, input)

    override suspend fun disable(
        sessionId: String,
        operationId: String,
        input: DisableNativePushRequest,
    ) = api.disable(sessionId, operationId, input)
}
