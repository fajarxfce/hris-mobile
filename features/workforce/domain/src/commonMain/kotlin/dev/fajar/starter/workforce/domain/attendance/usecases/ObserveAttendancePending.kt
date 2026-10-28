package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveAttendancePending(
    private val commands: AttendanceCommandRepository,
    private val reads: WorkforceReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObservePendingActions {
    override val group = PendingActionGroup.Attendance

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePending(workspaceId: String): Flow<AppResult<PendingActionSummary>> =
        sessions
            .observe()
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session = identity.value
                        if (session == null)
                            flowOf(calendarFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitWorkforce(
                                            workspaceId,
                                            selected.value,
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
                    is AppResult.Success -> {
                        val access = admitted.value
                        reads.observeAdmission(access).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else
                                        observeStored(access).map { result ->
                                            val latest = reads.admission(access)
                                            currentCoroutineContext().ensureActive()
                                            when (latest) {
                                                is AppResult.Failed -> latest
                                                is AppResult.Success ->
                                                    latest.value.denial?.let {
                                                        AppResult.Failed(it)
                                                    } ?: result
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun observeStored(access: WorkforceAccess) =
        commands.observeAll(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> {
                    val rows = result.value
                    if (rows.any { !it.command.belongsTo(access) })
                        calendarFailure("client.attendance_scope_changed", FailureKind.AccessDenied)
                    else
                        AppResult.Success(
                            PendingActionSummary(
                                queued =
                                    rows.count { it.outcome is AttendanceCommandOutcome.Pending },
                                unconfirmed =
                                    rows.count {
                                        it.outcome is AttendanceCommandOutcome.Unconfirmed
                                    },
                                needsReview =
                                    rows.count { it.outcome is AttendanceCommandOutcome.Rejected },
                            )
                        )
                }
            }
        }
}
