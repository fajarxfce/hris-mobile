package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted owned facts. Callers own admission and SDK lifecycle; no network or retries here. */
interface CollaborationHistoryRepository {
    /** Indexed original-alias or tombstone identity, with the alias's own provider timestamp. */
    suspend fun getEventFact(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationHistoryEventFact?>

    /**
     * One coherent window of 1..150 originals. Exclusive after/before anchors are mutually
     * exclusive. Up to 50 overlapping originals preserve the viewport when moving either way;
     * overlap must be smaller than limit. Cursors never represent provider positions.
     */
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String? = null,
        after: String? = null,
        limit: Int = 50,
        before: String? = null,
        overlap: Int = 0,
    ): Flow<AppResult<CollaborationHistoryPage>>

    /** Resolves any retained provider alias without scanning the conversation. */
    suspend fun getByEvent(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationHistoryMessage?>

    /** Recovers an original acknowledgement even when its provider event ID was never journaled. */
    suspend fun getByLogicalId(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        logicalId: String,
    ): AppResult<CollaborationHistoryMessage?>

    /**
     * Atomically retains 1..16 original/redacted events, aliases and checkpoint. Conflicting
     * original content fails the complete batch; false means an ownership or checkpoint race. No
     * alias eviction, cursor advancement, implicit retry, edits or command retirement.
     */
    suspend fun merge(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        changes: List<CollaborationHistoryChange>,
    ): AppResult<Boolean>
}
