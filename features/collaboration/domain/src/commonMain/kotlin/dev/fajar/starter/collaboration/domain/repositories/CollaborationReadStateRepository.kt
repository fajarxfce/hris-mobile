package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Encrypted local facts and a bounded metadata-intent queue. No SDK, admission or retry policy. */
interface CollaborationReadStateRepository {
    /** Fixed, bounded event selection for this subscription; no message contents or SDK I/O. */
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): Flow<AppResult<CollaborationReadState>>

    /** One coherent room checkpoint and exact coverage for 0..128 distinct queried event IDs. */
    suspend fun read(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ): AppResult<CollaborationReadState>

    /**
     * Atomically adds 1..128 exact read facts and appends the caller's intent to a queue of at
     * most128. Existing read facts and other pending intents are never removed. The caller
     * validates owned history. False preserves all facts and means the observed checkpoint or
     * account no longer matches.
     */
    suspend fun mark(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        expected: CollaborationReadStateRevision?,
        eventIds: List<String>,
        intent: CollaborationReadReceiptIntent,
    ): AppResult<Boolean>

    /** Removes only this identical queued intent and retains its acknowledgement atomically. */
    suspend fun acknowledge(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        intent: CollaborationReadReceiptIntent,
    ): AppResult<Boolean>
}
