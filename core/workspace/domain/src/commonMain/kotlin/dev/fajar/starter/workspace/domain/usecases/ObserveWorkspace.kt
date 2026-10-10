package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlinx.coroutines.flow.*

/** Consumers never see a company owned by another account or backend. */
class ObserveWorkspace(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) {
    operator fun invoke(): Flow<AppResult<Workspace?>> =
        combine(sessions.observe(), workspaces.observe()) { session, selected ->
                when {
                    session is AppResult.Failed -> session
                    selected is AppResult.Failed -> selected
                    else -> {
                        val current = (session as AppResult.Success).value
                        val workspace = (selected as AppResult.Success).value
                        AppResult.Success(
                            workspace?.takeIf {
                                current?.id == it.sessionId &&
                                    current.binding.audience == it.audience
                            }
                        )
                    }
                }
            }
            .distinctUntilChanged()
}
