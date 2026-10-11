package dev.fajar.starter.sync.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.domain.entities.*

/** Bounded invalidation pages; feature use cases own hydration and cursor publication. */
interface SyncFeedRepository {
    suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        collections: Set<SyncCollection>,
        cursor: String?,
        limit: Int = 50,
    ): AppResult<SyncBootstrapPage>

    suspend fun changes(
        sessionId: String,
        companyId: String,
        collections: Set<SyncCollection>,
        cursor: String,
        limit: Int = 50,
    ): AppResult<SyncChangePage>
}
