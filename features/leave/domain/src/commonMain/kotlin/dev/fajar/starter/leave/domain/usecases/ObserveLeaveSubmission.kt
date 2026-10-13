@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeaveSubmissionCommandRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveLeaveSubmission(
    private val commands: LeaveSubmissionCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        employeeId: String,
    ): Flow<AppResult<LeaveSubmissionRecord?>> {
        if (Uuid.parseOrNull(employeeId) == null) return flowOf(leaveFailure("invalid_employee"))
        return sessions
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
                                        when (
                                            val admitted =
                                                admitLeave(
                                                    workspaceId,
                                                    workspace.value,
                                                    session,
                                                    build,
                                                    clock.now(),
                                                )
                                        ) {
                                            is AppResult.Failed -> admitted
                                            is AppResult.Success ->
                                                when (
                                                    val permitted =
                                                        admitSelfLeavePreparation(admitted.value)
                                                ) {
                                                    is AppResult.Failed -> permitted
                                                    is AppResult.Success -> admitted
                                                }
                                        }
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success -> commands.observe(admitted.value, employeeId)
                }
            }
    }
}
