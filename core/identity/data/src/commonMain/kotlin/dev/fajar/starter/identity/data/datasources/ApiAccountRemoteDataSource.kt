package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.api.AccountApi
import org.koin.core.annotation.Single

@Single
class ApiAccountRemoteDataSource(private val api: AccountApi) : AccountRemoteDataSource {
    override suspend fun current(sessionId: String) = api.current(sessionId)
}
