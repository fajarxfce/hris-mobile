package dev.fajar.starter.expenses.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseCacheRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveExpenseClaim(
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
    ): Flow<AppResult<ExpenseSnapshot<ExpenseView>?>> {
        val valid = validateExpenseId(id)
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
                    is AppResult.Success ->
                        cache.observeDetails(admitted.value).map { result ->
                            when (result) {
                                is AppResult.Failed -> result
                                is AppResult.Success ->
                                    when (
                                        val ownership =
                                            validateStoredExpense(admitted.value, result.value)
                                    ) {
                                        is AppResult.Failed -> ownership
                                        is AppResult.Success ->
                                            AppResult.Success(
                                                result.value?.takeIf { it.value.claim.id == id }
                                            )
                                    }
                            }
                        }
                }
            }
    }
}
