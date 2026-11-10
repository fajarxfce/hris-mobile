package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Durable organization intent, separate from disposable replicas and legacy company commands. */
interface CollaborationSpaceMessageRepository {
    /**
     * Bounded account inventory; observing owned facts never acquires transport or starts delivery.
     */
    fun observe(
        access: CollaborationSpaceLocalAccess,
        conversationId: String?,
    ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>>

    /** Exact active or authenticated terminal facts; never revives completed/cancelled work. */
    fun observeRecord(
        access: CollaborationSpaceLocalAccess,
        id: String,
    ): Flow<AppResult<CollaborationSpaceMessageRecord?>>

    suspend fun get(
        access: CollaborationSpaceLocalAccess,
        id: String,
    ): AppResult<CollaborationSpaceMessageRecord?>

    /**
     * New intent and recovery work commit atomically. Exact retained replay creates no new wake. A
     * failed/cancelled response does not prove absence; the caller retains the original ID/content
     * and performs exact owned readback before choosing another action. The application use case
     * owns scheduling.
     */
    suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        intent: CollaborationSpaceMessageIntent,
    ): AppResult<CollaborationMessageInsertion>

    /** Compare the complete retained record before advancing its original physical mapping. */
    suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceMessageRecord,
        state: CollaborationMessageState,
    ): AppResult<Boolean>
}
