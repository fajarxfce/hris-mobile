package dev.fajar.starter.sync.data.datasources

import dev.fajar.starter.sync.data.api.SyncFeedApi
import org.koin.core.annotation.Single

@Single
class ApiSyncFeedRemoteDataSource(private val api: SyncFeedApi) : SyncFeedRemoteDataSource {
    override suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String?,
        limit: Int,
    ) = api.bootstrap(sessionId, companyId, collections, cursor, limit)

    override suspend fun changes(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String,
        limit: Int,
    ) = api.changes(sessionId, companyId, collections, cursor, limit)
}
