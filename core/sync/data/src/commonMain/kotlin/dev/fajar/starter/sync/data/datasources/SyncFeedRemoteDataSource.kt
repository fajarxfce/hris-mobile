package dev.fajar.starter.sync.data.datasources

import dev.fajar.starter.sync.data.dto.*

interface SyncFeedRemoteDataSource {
    suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String?,
        limit: Int,
    ): SyncBootstrapDto

    suspend fun changes(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String,
        limit: Int,
    ): SyncChangesDto
}
