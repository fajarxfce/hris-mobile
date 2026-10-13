package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.validateLocalWorkspaceOwnership
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Observe the locally owned selection without coupling reads to credential freshness. */
class ObserveWorkspace(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<Workspace?>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null) flowOf(AppResult.Success(null))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        AppResult.Success(
                                            selected.value?.takeIf { workspace ->
                                                validateLocalWorkspaceOwnership(
                                                    workspace.id,
                                                    workspace,
                                                    session,
                                                ) is
                                                    AppResult.Success
                                            }
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
}
