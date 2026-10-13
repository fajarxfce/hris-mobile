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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveExpenses(
    private val cache: ExpenseCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(workspaceId: String): Flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>> =
        sessions
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
                        cache.observe(admitted.value).flatMapLatest { result ->
                            flow<AppResult<ExpenseSnapshot<ExpenseOverview>?>> {
                                if (result is AppResult.Failed) {
                                    emit(result)
                                    return@flow
                                }
                                val snapshot = (result as AppResult.Success).value
                                val validity =
                                    validateExpenseCache(admitted.value, snapshot, clock.now())
                                if (validity is AppResult.Failed) {
                                    emit(validity)
                                    return@flow
                                }
                                emit(result)
                                val deadline =
                                    minOf(
                                        admitted.value.readUntil,
                                        snapshot?.expiresAt ?: admitted.value.readUntil,
                                    )
                                delay((deadline - clock.now()).inWholeMilliseconds.coerceAtLeast(0))
                                emit(
                                    expenseFailure(
                                        if (admitted.value.readUntil <= deadline)
                                            admitted.value.expiryCode
                                        else "client.expense_cache_expired",
                                        FailureKind.AccessDenied,
                                    )
                                )
                            }
                        }
                }
            }
}
