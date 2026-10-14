package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.expenses.domain.repositories.ExpenseCacheRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveExpenseClaim(
    private val replicas: ExpenseReplicaRepository,
    private val cache: ExpenseCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        id: String,
        historyAfter: Long? = null,
    ): Flow<AppResult<ExpenseSnapshot<ExpenseView>?>> {
        val valid = validateExpenseId(id)
        if (valid is AppResult.Failed) return flowOf(valid)
        val cursor = validateExpenseHistoryCursor(historyAfter)
        if (cursor is AppResult.Failed) return flowOf(cursor)
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
                    is AppResult.Success -> observeReplica(admitted.value, id, historyAfter)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: ExpenseAccess,
        id: String,
        historyAfter: Long?,
    ): Flow<AppResult<ExpenseSnapshot<ExpenseView>?>> =
        replicas.observeAdmission(access).flatMapLatest { admission ->
            when (admission) {
                is AppResult.Failed -> flowOf(admission)
                is AppResult.Success -> {
                    val denial = admission.value.denial
                    if (denial != null) flowOf(AppResult.Failed(denial))
                    else
                        replicas.observeDetails(access, id, historyAfter).flatMapLatest { loaded ->
                            when (loaded) {
                                is AppResult.Failed -> flowOf(loaded)
                                is AppResult.Success -> {
                                    val result = loaded.value
                                    when {
                                        result.snapshot != null ->
                                            flowOf(
                                                when (
                                                    val owner =
                                                        validateStoredExpense(
                                                            access,
                                                            result.snapshot,
                                                        )
                                                ) {
                                                    is AppResult.Failed -> owner
                                                    is AppResult.Success ->
                                                        AppResult.Success(result.snapshot)
                                                }
                                            )
                                        result.current != null && historyAfter != null ->
                                            observeLegacy(access, id, historyAfter).map { legacy ->
                                                when (legacy) {
                                                    is AppResult.Failed -> legacy
                                                    is AppResult.Success -> {
                                                        val current = requireNotNull(result.current)
                                                        val older = legacy.value?.value
                                                        val view =
                                                            older
                                                                ?.takeIf {
                                                                    it.claim.employeeId ==
                                                                        current.value.claim
                                                                            .employeeId
                                                                }
                                                                ?.let {
                                                                    current.value.copy(
                                                                        history = it.history,
                                                                        historyAfter = historyAfter,
                                                                    )
                                                                }
                                                        if (
                                                            view == null ||
                                                                validateExpenseView(view) is
                                                                    AppResult.Failed
                                                        )
                                                            AppResult.Success(current)
                                                        else
                                                            AppResult.Success(
                                                                current.copy(value = view)
                                                            )
                                                    }
                                                }
                                            }
                                        result.checkpoint?.refreshedAt == null ->
                                            observeLegacy(access, id, historyAfter)
                                        else -> flowOf(AppResult.Success(null))
                                    }
                                }
                            }
                        }
                }
            }
        }

    private fun observeLegacy(
        access: ExpenseAccess,
        id: String,
        historyAfter: Long?,
    ): Flow<AppResult<ExpenseSnapshot<ExpenseView>?>> =
        cache.observeDetails(access).map { loaded ->
            when (loaded) {
                is AppResult.Failed -> loaded
                is AppResult.Success ->
                    when (val owner = validateStoredExpense(access, loaded.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success ->
                            AppResult.Success(
                                loaded.value?.takeIf {
                                    it.value.claim.id == id && it.value.historyAfter == historyAfter
                                }
                            )
                    }
            }
        }
}
