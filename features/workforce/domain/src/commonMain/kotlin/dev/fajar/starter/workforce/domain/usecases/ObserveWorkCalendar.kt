package dev.fajar.starter.workforce.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.replica.policies.*
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import dev.fajar.starter.workforce.domain.repositories.WorkCalendarCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveWorkCalendar(
    private val replicas: WorkforceReplicaRepository,
    private val cache: WorkCalendarCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: WorkCalendarSelection = WorkCalendarSelection(),
    ): Flow<AppResult<WorkCalendarSnapshot?>> {
        val valid = validateCalendarSelection(selection)
        if (valid is AppResult.Failed) return flowOf(valid)
        return sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(calendarFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitWorkforce(
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
                    is AppResult.Success -> observeReplica(admitted.value, selection)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: WorkforceAccess,
        selection: WorkCalendarSelection,
    ): Flow<AppResult<WorkCalendarSnapshot?>> =
        replicas.observeAdmission(access).flatMapLatest { admitted ->
            when (admitted) {
                is AppResult.Failed -> flowOf(admitted)
                is AppResult.Success -> {
                    val denial = admitted.value.denial
                    if (denial != null) flowOf(AppResult.Failed(denial))
                    else
                        replicas.observeDirectory(access).flatMapLatest { result ->
                            when (result) {
                                is AppResult.Failed -> flowOf(result)
                                is AppResult.Success -> {
                                    val metadata = result.value
                                    val checkpoint = metadata.checkpoint
                                    if (checkpoint == null) observeLegacy(access, selection)
                                    else {
                                        val owner =
                                            validateStoredWorkforceReplica(access, checkpoint)
                                        if (owner is AppResult.Failed)
                                            return@flatMapLatest flowOf(owner)
                                        val directory =
                                            currentWorkforceDirectory(
                                                requireNotNull(metadata.directory),
                                                clock.now(),
                                            )
                                        val dates = calendarPeriod(directory.asOf, selection.from)
                                        if (dates is AppResult.Failed)
                                            return@flatMapLatest flowOf(dates)
                                        val period = (dates as AppResult.Success).value
                                        val employee =
                                            selectWorkforceEmployee(
                                                directory,
                                                selection.employeeId,
                                                selection.after,
                                            )
                                        if (employee is AppResult.Failed)
                                            return@flatMapLatest flowOf(employee)
                                        val chosen =
                                            selection.copy(
                                                employeeId =
                                                    (employee as AppResult.Success).value?.id,
                                                from = period.from,
                                            )
                                        val employeeId = chosen.employeeId
                                        if (employeeId == null)
                                            flowOf(
                                                calendarReplicaSnapshot(
                                                    access,
                                                    checkpoint,
                                                    directory,
                                                    chosen,
                                                    period,
                                                    null,
                                                )
                                            )
                                        else
                                            replicas
                                                .observeFacts(access, employeeId, period)
                                                .mapNotNull { queried ->
                                                    when (queried) {
                                                        is AppResult.Failed -> queried
                                                        is AppResult.Success -> {
                                                            val facts = queried.value
                                                            if (
                                                                facts.checkpoint?.generation !=
                                                                    checkpoint.generation ||
                                                                    facts.checkpoint.revision !=
                                                                        checkpoint.revision
                                                            )
                                                                null
                                                            else
                                                                calendarReplicaSnapshot(
                                                                    access,
                                                                    checkpoint,
                                                                    directory,
                                                                    chosen,
                                                                    period,
                                                                    facts,
                                                                )
                                                        }
                                                    }
                                                }
                                    }
                                }
                            }
                        }
                }
            }
        }

    /** Only the acquired legacy view is available until the first complete replica publishes. */
    private fun observeLegacy(
        access: WorkforceAccess,
        selection: WorkCalendarSelection,
    ): Flow<AppResult<WorkCalendarSnapshot?>> =
        cache.observe(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val owner = validateStoredCalendar(access, result.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success -> {
                            val saved = result.value
                            if (
                                saved != null &&
                                    (selection.employeeId == null ||
                                        selection.employeeId == saved.selection.employeeId) &&
                                    (selection.from == null ||
                                        selection.from == saved.period.from) &&
                                    selection.after == saved.selection.after
                            )
                                result
                            else AppResult.Success(null)
                        }
                    }
            }
        }
}
