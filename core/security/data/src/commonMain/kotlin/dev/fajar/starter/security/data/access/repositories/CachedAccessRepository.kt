package dev.fajar.starter.security.data.access.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.security.data.access.datasources.*
import dev.fajar.starter.security.data.access.mappers.toAccessSnapshot
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [AccessRepository::class])
class CachedAccessRepository(
    private val remote: AccessRemoteDataSource,
    private val cache: AccessCache,
) : AccessRepository {
    override fun observe(sessionId: String) =
        safeStorageFlow(cache.observe(sessionId).map { it?.toAccessSnapshot(sessionId) })

    override suspend fun cached(sessionId: String) = safeStorageCall {
        cache.read(sessionId)?.toAccessSnapshot(sessionId)
    }

    override suspend fun invalidate(sessionId: String) = safeStorageCall { cache.remove(sessionId) }

    override suspend fun refresh(sessionId: String): AppResult<Unit> {
        val response =
            when (
                val result = safeApiCall {
                    remote.fetch(sessionId).also { it.toAccessSnapshot(sessionId) }
                }
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return safeStorageCall { cache.write(sessionId, response) }
    }
}
