package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Owned encrypted activity index; transport, discovery and source admission are independent. */
interface CollaborationSpaceInboxRepository {
    fun observePage(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 50,
    ): Flow<AppResult<CollaborationSpaceInboxPage>>

    suspend fun conversation(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        candidateLimit: Int = 2,
    ): AppResult<CollaborationSpaceInboxConversation>

    suspend fun activities(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        sources: List<CollaborationSpaceInboxSource>,
    ): AppResult<CollaborationSpaceInboxActivities>

    /**
     * Retain an existing original and fresh repair demand atomically. Null repair only probes: a
     * semantic replay returns RETAINED without acquiring progress; new work returns DEMAND_REQUIRED
     * without writing. CHANGED requires a fresh source/index observation.
     */
    suspend fun seed(
        access: CollaborationSpaceLocalAccess,
        publication: CollaborationSpaceInboxPublication,
        repair: CollaborationSpaceInboxRepairPublication?,
    ): AppResult<CollaborationSpaceInboxSeedResult>

    /** Projection-only seeding/repair. Source changes use the source repository's joint commit. */
    suspend fun publish(
        access: CollaborationSpaceLocalAccess,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean>
}
