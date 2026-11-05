package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationSummarySyncRepository {
    suspend fun bootstrap(
        owner: CollaborationOwner,
        cursor: String?,
        limit: Int,
    ): AppResult<CollaborationSummaryBootstrap>

    suspend fun changes(
        owner: CollaborationOwner,
        cursor: String,
        limit: Int,
    ): AppResult<CollaborationSummaryChanges>
}
