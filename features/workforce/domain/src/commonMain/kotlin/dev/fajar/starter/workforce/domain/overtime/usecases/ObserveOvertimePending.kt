package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.working.repositories.*
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveOvertimePending(
    private val plans: OvertimePlanWorkingCopyRepository,
    private val actions: OvertimeActionWorkingCopyRepository,
    private val commands: OvertimeCommandRepository,
    private val reads: OvertimeReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObservePendingActions {
    override val group = PendingActionGroup.Overtime

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
                            flowOf(overtimeFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitOvertime(
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
                        val allowed = admitSelfOvertimeCommands(access)
                        if (allowed is AppResult.Failed) flowOf(allowed)
                        else
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
        combine(
            plans.observePage(access),
            actions.observePage(access),
            commands.observeAll(access),
        ) { planResult, actionResult, commandResult ->
            when {
                planResult is AppResult.Failed -> planResult
                actionResult is AppResult.Failed -> actionResult
                commandResult is AppResult.Failed -> commandResult
                else -> {
                    val plans = (planResult as AppResult.Success).value
                    val actions = (actionResult as AppResult.Success).value
                    val commands = (commandResult as AppResult.Success).value
                    if (commands.any { !it.command.belongsTo(access) })
                        overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
                    else {
                        val planCount =
                            plans.items.count { copy ->
                                copy.hasEdits &&
                                    commands.none {
                                        it.command.employeeId == copy.employeeId &&
                                            it.command.payload is OvertimeCommandPayload.Plan &&
                                            it.command.payload.workingRevision == copy.revision
                                    }
                            }
                        val actionCount =
                            actions.items.count { copy ->
                                copy.hasEdits &&
                                    commands.none {
                                        val command = it.command
                                        command.employeeId == copy.employeeId &&
                                            command.requestId == copy.requestId &&
                                            command.payload.workingRevision == copy.revision &&
                                            when (command.payload) {
                                                is OvertimeCommandPayload.Actual ->
                                                    copy.action == OvertimeAction.SUBMIT_ACTUAL
                                                is OvertimeCommandPayload.Withdrawal ->
                                                    copy.action == OvertimeAction.WITHDRAW
                                                is OvertimeCommandPayload.Plan -> false
                                            }
                                    }
                            }
                        AppResult.Success(
                            PendingActionSummary(
                                drafts = planCount + actionCount,
                                queued =
                                    commands.count { it.outcome is OvertimeCommandOutcome.Pending },
                                unconfirmed =
                                    commands.count {
                                        it.outcome is OvertimeCommandOutcome.Unconfirmed
                                    },
                                needsReview =
                                    commands.count {
                                        it.outcome is OvertimeCommandOutcome.Rejected
                                    },
                                moreDrafts = plans.nextCursor != null || actions.nextCursor != null,
                            )
                        )
                    }
                }
            }
        }
}
