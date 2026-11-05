package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSummarySyncRemoteSource {
    suspend fun bootstrap(
        session: String,
        company: String,
        cursor: String?,
        limit: Int,
    ): CollaborationSummaryBootstrapDto

    suspend fun changes(
        session: String,
        company: String,
        cursor: String,
        limit: Int,
    ): CollaborationSummaryChangesDto
}
