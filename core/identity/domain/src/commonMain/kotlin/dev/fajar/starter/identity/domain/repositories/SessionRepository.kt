package dev.fajar.starter.identity.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.Session
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    val persistent: Boolean

    fun observe(): Flow<AppResult<Session?>>

    suspend fun current(): AppResult<Session?>

    /** False means another session transition already committed. */
    suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean>

    /**
     * Atomically clears this local identity, regardless of token rotation; false means it is gone.
     */
    suspend fun clear(sessionId: String): AppResult<Boolean>
}
