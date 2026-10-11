package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeaveCommandRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveLeaveAction(
    private val commands: LeaveCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        requestId: String,
    ): Flow<AppResult<LeaveCommandRecord?>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(leaveFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { workspace ->
                                when (workspace) {
                                    is AppResult.Failed -> workspace
                                    is AppResult.Success ->
                                        admitLeave(
                                            workspaceId,
                                            workspace.value,
                                            session,
                                            build,
                                            clock.now(),
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success ->
                        commands.observe(admitted.value, requestId).flatMapLatest { result ->
                            flow<AppResult<LeaveCommandRecord?>> {
                                val access = admitted.value
                                val deadline = minOf(access.readUntil, access.sessionExpiresAt)
                                val remaining = deadline - clock.now()
                                if (remaining.isPositive()) {
                                    emit(result)
                                    delay(remaining)
                                }
                                emit(
                                    leaveFailure(
                                        if (access.sessionExpiresAt <= deadline) "session_revoked"
                                        else access.expiryCode,
                                        FailureKind.AccessDenied,
                                    )
                                )
                            }
                        }
                }
            }
}
