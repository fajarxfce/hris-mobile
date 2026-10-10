package dev.fajar.starter.workspace.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workspace.domain.entities.Workspace
import kotlinx.coroutines.flow.Flow

interface WorkspaceRepository {
    fun observe(): Flow<AppResult<Workspace?>>

    suspend fun current(): AppResult<Workspace?>

    suspend fun compareAndSet(expected: Workspace?, updated: Workspace?): AppResult<Boolean>

    suspend fun permissions(sessionId: String, companyId: String): AppResult<Set<String>>
}
