package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.replica.policies.*
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveAttendance(
    private val replicas: WorkforceReplicaRepository,
    private val cache: AttendanceCacheRepository,
    private val commands: AttendanceCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: AttendanceSelection = AttendanceSelection(),
    ): Flow<AppResult<AttendanceView>> {
        val valid = validateAttendanceSelection(selection)
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
                    is AppResult.Success ->
                        combine(
                            observeReplica(admitted.value, selection),
                            commands.observe(admitted.value),
                        ) { result, records ->
                            when {
                                result is AppResult.Failed -> result
                                records is AppResult.Failed -> records
                                else -> {
                                    val snapshot = (result as AppResult.Success).value
                                    when (
                                        val ownership =
                                            validateStoredAttendance(admitted.value, snapshot)
                                    ) {
                                        is AppResult.Failed -> ownership
                                        is AppResult.Success ->
                                            AppResult.Success(
                                                AttendanceView(
                                                    snapshot,
                                                    (records as AppResult.Success).value,
                                                )
                                            )
                                    }
                                }
                            }
                        }
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: WorkforceAccess,
        selection: AttendanceSelection,
    ): Flow<AppResult<AttendanceSnapshot?>> =
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
                                        val dates = attendancePeriod(directory.asOf, selection)
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
                                        val employeeId = (employee as AppResult.Success).value?.id
                                        if (employeeId == null)
                                            flowOf(
                                                attendanceReplicaSnapshot(
                                                    access,
                                                    checkpoint,
                                                    directory,
                                                    null,
                                                    period,
                                                    selection.after,
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
                                                                attendanceReplicaSnapshot(
                                                                    access,
                                                                    checkpoint,
                                                                    directory,
                                                                    employeeId,
                                                                    period,
                                                                    selection.after,
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

    private fun observeLegacy(
        access: WorkforceAccess,
        selection: AttendanceSelection,
    ): Flow<AppResult<AttendanceSnapshot?>> =
        cache.observe(access, selection.window).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val owner = validateStoredAttendance(access, result.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success -> {
                            val saved = result.value
                            if (
                                saved != null &&
                                    (selection.employeeId == null ||
                                        selection.employeeId == saved.employeeId) &&
                                    (selection.from == null ||
                                        selection.from == saved.period.from) &&
                                    selection.after == saved.after
                            )
                                result
                            else AppResult.Success(null)
                        }
                    }
            }
        }
}
