package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Captured owned source changes. The caller separately admits current account/Group access. */
interface CollaborationSpaceHistoryChangesRepository {
    /**
     * Read at most1..16 raw records in the published generation. Authenticate source facts before
     * returning bounded event references, including hidden/redacted originals and edit targets. No
     * acquisition loop, key creation or incoming-reference completion is performed here.
     */
    suspend fun changes(
        source: CollaborationSpaceHistoryLocation.Space,
        cursor: CollaborationHistoryChangeCursor? = null,
        limit: Int = 16,
    ): AppResult<CollaborationHistoryChanges>
}
