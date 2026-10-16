package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveOvertimeCommand(
    private val commands: OvertimeCommandRepository,
    private val cache: OvertimeRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        slot: OvertimeCommandSlot,
    ): Flow<AppResult<OvertimeCommandRecord?>> {
        val valid = validateOvertimeCommandSlot(slot)
        if (valid is AppResult.Failed) return flowOf(valid)
        return sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(overtimeFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { workspace ->
                                when (workspace) {
                                    is AppResult.Failed -> workspace
                                    is AppResult.Success ->
                                        when (
                                            val admitted =
                                                admitOvertime(
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
                                                    val allowed =
                                                        admitSelfOvertimeCommands(admitted.value)
                                                ) {
                                                    is AppResult.Failed -> allowed
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
                    is AppResult.Success ->
                        cache.observeAdmission(admitted.value).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else observeStoredCommand(admitted.value, slot)
                                }
                            }
                        }
                }
            }
    }

    private fun observeStoredCommand(
        access: WorkforceAccess,
        slot: OvertimeCommandSlot,
    ): Flow<AppResult<OvertimeCommandRecord?>> =
        commands.observe(access, slot).map { result ->
            val record = (result as? AppResult.Success)?.value
            if (
                record != null &&
                    (!record.command.belongsTo(access) || record.command.slot() != slot)
            )
                overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
            else result
        }
}
