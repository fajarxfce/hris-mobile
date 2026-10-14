package dev.fajar.starter.expenses.domain.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.*
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveExpenseWorkingCopy(
    private val copies: ExpenseWorkingCopyRepository,
    private val replicas: ExpenseReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        slot: ExpenseWorkingSlot,
    ): Flow<AppResult<ExpenseWorkingCopy?>> {
        val valid = validateExpenseWorkingSlot(slot)
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
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitExpenses(
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
                    is AppResult.Success -> {
                        val editing = admitExpenseEditing(admitted.value)
                        if (editing is AppResult.Failed) flowOf(editing)
                        else observeAdmitted(admitted.value, slot)
                    }
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAdmitted(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
    ): Flow<AppResult<ExpenseWorkingCopy?>> =
        replicas.observeAdmission(access).flatMapLatest { admission ->
            when (admission) {
                is AppResult.Failed -> flowOf(admission)
                is AppResult.Success -> {
                    val denial = admission.value.denial
                    if (denial != null) flowOf(AppResult.Failed(denial))
                    else
                        copies.observe(access, slot).map { saved ->
                            when (saved) {
                                is AppResult.Failed -> saved
                                is AppResult.Success -> {
                                    val valid =
                                        saved.value?.let { validateExpenseWorkingCopy(slot, it) }
                                    if (valid is AppResult.Failed) valid else saved
                                }
                            }
                        }
                }
            }
        }
}
