package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.working.repositories.LeaveWorkingCopyRepository
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveLeavePending(
    private val copies: LeaveWorkingCopyRepository,
    private val submissions: LeaveSubmissionCommandRepository,
    private val commands: LeaveCommandRepository,
    private val reads: LeaveReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObservePendingActions {
    override val group = PendingActionGroup.Leave

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
                            flowOf(leaveFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitLeave(
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
                        val allowed = admitSelfLeavePreparation(access)
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

    private fun observeStored(access: LeaveAccess) =
        combine(
            copies.observePage(access),
            submissions.observeAll(access),
            commands.observeAll(access),
        ) { draftResult, submissionResult, commandResult ->
            when {
                draftResult is AppResult.Failed -> draftResult
                submissionResult is AppResult.Failed -> submissionResult
                commandResult is AppResult.Failed -> commandResult
                else -> {
                    val drafts = (draftResult as AppResult.Success).value
                    val submissions = (submissionResult as AppResult.Success).value
                    val commands = (commandResult as AppResult.Success).value
                    if (
                        submissions.any { !it.command.belongsTo(access) } ||
                            commands.any { !it.command.belongsTo(access) }
                    )
                        leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
                    else {
                        val outcomes = submissions.map { it.outcome } + commands.map { it.outcome }
                        AppResult.Success(
                            PendingActionSummary(
                                drafts =
                                    drafts.items.count { copy ->
                                        copy.hasEdits &&
                                            submissions.none {
                                                it.command.selection.employeeId ==
                                                    copy.employeeId &&
                                                    it.command.workingRevision == copy.revision
                                            }
                                    },
                                queued = outcomes.count { it is LeaveCommandOutcome.Pending },
                                unconfirmed =
                                    outcomes.count { it is LeaveCommandOutcome.Unconfirmed },
                                needsReview = outcomes.count { it is LeaveCommandOutcome.Rejected },
                                moreDrafts = drafts.nextCursor != null,
                            )
                        )
                    }
                }
            }
        }
}
