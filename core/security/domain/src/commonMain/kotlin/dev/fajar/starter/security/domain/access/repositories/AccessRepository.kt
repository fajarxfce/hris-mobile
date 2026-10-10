package dev.fajar.starter.security.domain.access.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.security.domain.access.entities.AccessSnapshot
import kotlinx.coroutines.flow.Flow

interface AccessRepository {
    fun observe(sessionId: String): Flow<AppResult<AccessSnapshot?>>

    suspend fun cached(sessionId: String): AppResult<AccessSnapshot?>

    suspend fun invalidate(sessionId: String): AppResult<Unit>

    suspend fun refresh(sessionId: String): AppResult<Unit>
}
