package dev.fajar.starter.security.data.access.datasources

import dev.fajar.starter.security.data.access.api.AccessApi
import org.koin.core.annotation.Single

@Single
class ApiAccessRemoteDataSource(private val api: AccessApi) : AccessRemoteDataSource {
    override suspend fun fetch(sessionId: String) = api.fetch(sessionId)
}
