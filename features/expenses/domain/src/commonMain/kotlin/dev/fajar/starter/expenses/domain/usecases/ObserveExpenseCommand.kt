package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.ExpenseCommandRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveExpenseCommand(
    private val commands: ExpenseCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val replicas: ExpenseReplicaRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        slot: ExpenseCommandSlot,
    ): Flow<AppResult<ExpenseCommandRecord?>> {
        val valid = validateExpenseCommandSlot(slot)
        if (valid is AppResult.Failed) return flowOf(valid)
        return sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(expenseFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { workspace ->
                                when (workspace) {
                                    is AppResult.Failed -> workspace
                                    is AppResult.Success ->
                                        when (
                                            val admitted =
                                                admitExpenses(
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
                                                        admitExpenseEditing(admitted.value)
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
                    is AppResult.Success -> observeAdmittedCommand(admitted.value, slot)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAdmittedCommand(
        access: ExpenseAccess,
        slot: ExpenseCommandSlot,
    ): Flow<AppResult<ExpenseCommandRecord?>> =
        replicas.observeAdmission(access).flatMapLatest { admission ->
            when (admission) {
                is AppResult.Failed -> flowOf(admission)
                is AppResult.Success -> {
                    val denial = admission.value.denial
                    if (denial != null) flowOf(AppResult.Failed(denial))
                    else
                        commands.observe(access, slot).map { result ->
                            val record = (result as? AppResult.Success)?.value
                            if (
                                record != null &&
                                    (!record.command.belongsTo(access) ||
                                        record.command.slot() != slot)
                            )
                                expenseFailure(
                                    "client.expense_scope_changed",
                                    FailureKind.AccessDenied,
                                )
                            else result
                        }
                }
            }
        }
}
