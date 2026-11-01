package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted owned facts. Callers own admission and SDK lifecycle; no network or retries here. */
interface CollaborationHistoryRepository {
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String? = null,
        after: String? = null,
        limit: Int = 50,
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
