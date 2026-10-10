package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository

class LeaveWorkspace(private val workspaces: WorkspaceRepository) {
    suspend operator fun invoke(workspaceId: String): AppResult<Unit> {
        val current =
            when (val result = workspaces.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (current?.id != workspaceId) return AppResult.Success(Unit)
        return when (val result = workspaces.compareAndSet(current, null)) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(Unit)
        }
    }
}
