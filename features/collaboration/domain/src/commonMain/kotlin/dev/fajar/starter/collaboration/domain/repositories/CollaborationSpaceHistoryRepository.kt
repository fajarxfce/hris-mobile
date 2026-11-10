package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Owned Space history facts. The caller separately admits local reads and incoming evidence. */
interface CollaborationSpaceHistoryRepository {
    /** Original event/tombstone evidence; never reads a Company partition. */
    suspend fun getEventFact(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationHistoryEventFact?>

    /**
     * Indexed local window; after/before are mutually exclusive local anchors, never provider
     * cursors.
     */
    fun observe(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String? = null,
        after: String? = null,
        limit: Int = 50,
        before: String? = null,
        overlap: Int = 0,
    ): Flow<AppResult<CollaborationHistoryPage>>

    suspend fun getByEvent(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationHistoryMessage?>

    suspend fun getByLogicalId(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        logicalId: String,
    ): AppResult<CollaborationHistoryMessage?>

    /**
     * Atomic1..16 original/redacted facts and authenticated indexes; replay does not change
     * revision.
     */
    suspend fun merge(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        changes: List<CollaborationHistoryChange>,
    ): AppResult<Boolean>
}
