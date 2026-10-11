@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.sync.data.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.sync.data.datasources.SyncFeedRemoteDataSource
import dev.fajar.starter.sync.data.mappers.*
import dev.fajar.starter.sync.domain.entities.SyncCollection
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteSyncFeedRepository(private val remote: SyncFeedRemoteDataSource) : SyncFeedRepository {
    override suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        collections: Set<SyncCollection>,
        cursor: String?,
        limit: Int,
    ) = safeApiCall {
        Uuid.parse(companyId)
        require(collections.isNotEmpty() && limit in 1..200)
        cursor?.let(::validateSyncCursor)
        val selection = collections.toSet()
        remote
            .bootstrap(
                sessionId,
                companyId,
                selection.map { it.name }.sorted().joinToString(","),
                cursor,
                limit,
            )
            .toPage(selection, cursor, limit)
    }

    override suspend fun changes(
        sessionId: String,
        companyId: String,
        collections: Set<SyncCollection>,
        cursor: String,
        limit: Int,
    ) = safeApiCall {
        Uuid.parse(companyId)
        require(collections.isNotEmpty() && limit in 1..200)
        validateSyncCursor(cursor)
        val selection = collections.toSet()
        remote
            .changes(
                sessionId,
                companyId,
                selection.map { it.name }.sorted().joinToString(","),
                cursor,
                limit,
            )
            .toPage(selection, cursor, limit)
    }
}
