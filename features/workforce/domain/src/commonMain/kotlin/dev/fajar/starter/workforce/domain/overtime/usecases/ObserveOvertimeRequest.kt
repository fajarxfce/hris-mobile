package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeRequestReplicaRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveOvertimeRequest(
    private val cache: OvertimeRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: OvertimeRequestSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>> {
        val valid = validateOvertimeRequestSelection(selection)
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
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitOvertime(
                                            workspaceId,
                                            current.value,
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
                        cache.observeAdmission(admitted.value).flatMapLatest { result ->
                            when (result) {
                                is AppResult.Failed -> flowOf(result)
                                is AppResult.Success -> {
                                    val denial = result.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else observeStoredRequest(admitted.value, selection)
                                }
                            }
                        }
                }
            }
    }

    private fun observeStoredRequest(access: WorkforceAccess, selection: OvertimeRequestSelection) =
        cache.observeRequest(access, selection).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val ownership = validateStoredOvertime(access, result.value)) {
                        is AppResult.Failed -> ownership
                        is AppResult.Success -> result
                    }
            }
        }
}
