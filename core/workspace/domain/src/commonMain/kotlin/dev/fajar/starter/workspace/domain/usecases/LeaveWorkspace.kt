package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LeaveWorkspace(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) {
    suspend operator fun invoke(workspaceId: String): AppResult<Unit> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(Unit)
            }
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val current =
                when (val result = workspaces.current(session.id)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            if (current?.id != workspaceId) return AppResult.Success(Unit)
            val result = workspaces.compareAndSet(session.id, current, null)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Failed -> return result
                is AppResult.Success -> if (result.value) return AppResult.Success(Unit)
            }
        }
        return AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "Company selection changed.",
                code = "client.workspace_changed",
            )
        )
    }
}
