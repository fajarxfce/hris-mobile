package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/** Encrypted acquired metadata; no message contents, provider credentials or implicit delivery. */
interface CollaborationListRepository {
    /** One retained owned conversation; no network acquisition. */
    suspend fun get(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationDirectSummary?>

    fun observe(
        access: CollaborationLocalAccess,
        selection: CollaborationListSelection,
    ): Flow<AppResult<CollaborationLocalList>>

    suspend fun checkpoint(
        access: CollaborationLocalAccess
    ): AppResult<CollaborationListCheckpoint?>

    suspend fun merge(
        access: CollaborationLocalAccess,
        expected: CollaborationListCheckpoint?,
        items: List<CollaborationDirectSummary>,
        acquiredAt: Instant,
    ): AppResult<Boolean>

    fun observeAdmission(
        access: CollaborationLocalAccess
    ): Flow<AppResult<CollaborationListAdmission>>

    suspend fun admission(access: CollaborationLocalAccess): AppResult<CollaborationListAdmission>

    suspend fun restrict(access: CollaborationLocalAccess, failure: Failure): AppResult<Boolean>

    suspend fun admit(
        access: CollaborationLocalAccess,
        expectedRevision: String?,
    ): AppResult<Boolean>
}
