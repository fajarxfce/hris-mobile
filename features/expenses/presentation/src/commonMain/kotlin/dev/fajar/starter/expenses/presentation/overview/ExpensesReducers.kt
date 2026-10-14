package dev.fajar.starter.expenses.presentation.overview

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache
import dev.fajar.starter.expenses.domain.replica.entities.ExpenseSyncOutcome

internal fun ExpensesState.withCachedExpenses(
    result: AppResult<ExpenseSnapshot<ExpenseOverview>?>
): ExpensesState =
    when (result) {
        is AppResult.Failed ->
            copy(content = null, loading = false, cacheFailure = result.failure, dialog = null)
        is AppResult.Success ->
            copy(
                content = result.value,
                loading = false,
                cacheFailure = null,
                selection = result.value?.value?.selection ?: selection,
                refreshFailure = refreshFailure?.takeUnless(::expenseFailureInvalidatesCache),
            )
    }

internal fun ExpensesState.withExpensesRefresh(
    result: AppResult<ExpenseSyncOutcome>
): ExpensesState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure =
                    refreshFailure?.takeIf(::expenseFailureInvalidatesCache) ?: result.failure,
                content = content?.takeUnless { expenseFailureInvalidatesCache(result.failure) },
                dialog = dialog?.takeUnless { expenseFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = refreshFailure?.takeIf(::expenseFailureInvalidatesCache),
            )
    }
