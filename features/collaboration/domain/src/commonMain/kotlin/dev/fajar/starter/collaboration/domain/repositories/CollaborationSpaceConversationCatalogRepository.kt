package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Local metadata inventory. The owning use case admits the account/Group and schedules work. */
interface CollaborationSpaceConversationCatalogRepository {
    /**
     * Published version changes only; never reads message bodies, creates keys or acquires API
     * data.
     */
    fun observeVersion(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceConversationCatalogVersion?>>

    /**
     * Reads at most [rawLimit] metadata indexes plus one lookahead, then authenticates the returned
     * pairs. The cursor freezes the generation/insertion revision and belongs to this
     * account/Group.
     */
    suspend fun read(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        rawLimit: Int = 50,
    ): AppResult<CollaborationSpaceConversationCatalogRead>
}
