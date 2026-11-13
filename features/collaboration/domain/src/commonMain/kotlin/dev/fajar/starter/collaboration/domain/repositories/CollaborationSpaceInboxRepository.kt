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

    /** Projection-only seeding/repair. Source changes use the source repository's joint commit. */
    suspend fun publish(
        access: CollaborationSpaceLocalAccess,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean>
}
