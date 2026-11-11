package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/**
 * Finite local discovery, excluding unpublished data and without reading event bodies or keys.
 * Inactive sessions expose no coordinates. An empty view grants no incoming-completion authority;
 * the consumer still owns admission and cancellation around this read.
 */
interface CollaborationIncomingHistoryLocatorRepository {
    suspend fun read(
        access: CollaborationLocalAccess,
        after: String? = null,
        limit: Int = 16,
    ): AppResult<CollaborationIncomingHistoryLocatorPage>

    suspend fun read(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 16,
    ): AppResult<CollaborationIncomingHistoryLocatorPage>
}
