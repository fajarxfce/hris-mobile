package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.identity.data.datasources.AccountRemoteDataSource
import dev.fajar.starter.identity.data.mappers.toAccount
import dev.fajar.starter.identity.domain.repositories.AccountRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteAccountRepository(private val remote: AccountRemoteDataSource) : AccountRepository {
    override suspend fun load(sessionId: String) = safeApiCall {
        remote.current(sessionId).toAccount()
    }
}
