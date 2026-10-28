package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveExpensePending(
    private val copies: ExpenseWorkingCopyRepository,
    private val commands: ExpenseCommandRepository,
    private val reads: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObservePendingActions {
    override val group = PendingActionGroup.Expenses

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
                            flowOf(expenseFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitExpenses(
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
                        val allowed = admitExpenseEditing(access)
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

    private fun observeStored(access: ExpenseAccess) =
        combine(copies.observePage(access), commands.observeAll(access)) {
            draftResult,
            commandResult ->
            when {
                draftResult is AppResult.Failed -> draftResult
                commandResult is AppResult.Failed -> commandResult
                else -> {
                    val drafts = (draftResult as AppResult.Success).value
                    val commands = (commandResult as AppResult.Success).value
                    if (commands.any { !it.command.belongsTo(access) })
                        expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
                    else
                        AppResult.Success(
                            PendingActionSummary(
                                drafts =
                                    drafts.items.count { copy ->
                                        copy.hasEdits &&
                                            commands.none {
                                                val command = it.command
                                                val normalized =
                                                    normalizeExpenseDraft(
                                                        copy.input,
                                                        copy.expectedVersion,
                                                    )
                                                command.claimId == copy.claimId &&
                                                    command.employeeId == copy.employee.id &&
                                                    copy.item == null &&
                                                    copy.input.reason.trim() == command.reason &&
                                                    normalized is AppResult.Success &&
                                                    normalized.value == command.payload
                                            }
                                    },
                                queued =
                                    commands.count { it.outcome is ExpenseCommandOutcome.Pending },
                                unconfirmed =
                                    commands.count {
                                        it.outcome is ExpenseCommandOutcome.Unconfirmed
                                    },
                                needsReview =
                                    commands.count { it.outcome is ExpenseCommandOutcome.Rejected },
                                moreDrafts = drafts.nextCursor != null,
                            )
                        )
                }
            }
        }
}
