package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Original Company coordinates remain distinct from the canonical Group conversation. */
interface CollaborationCompanyHistoryChangesRepository {
    suspend fun changes(
        source: CollaborationSpaceHistoryLocation.Original,
        cursor: CollaborationHistoryChangeCursor? = null,
        limit: Int = 16,
    ): AppResult<CollaborationHistoryChanges>
}
