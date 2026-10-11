package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Session ownership and expiry are checked independently from raw persistence. */
class ObserveWorkspace(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.System,
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
                            workspaces.observe(session.id).flatMapLatest { selected ->
                                flow<AppResult<Workspace?>> {
                                    if (selected is AppResult.Failed) {
                                        emit(selected)
                                        return@flow
                                    }
                                    val workspace = (selected as AppResult.Success).value
                                    if (
                                        workspace == null ||
                                            validateWorkspaceOwnership(
                                                workspace.id,
                                                workspace,
                                                session,
                                                clock.now(),
                                            ) is
                                                AppResult.Failed
                                    ) {
                                        emit(AppResult.Success(null))
                                        return@flow
                                    }
                                    emit(AppResult.Success(workspace))
                                    delay(workspace.admission.expiresAt - clock.now())
                                    emit(AppResult.Success(null))
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
}
