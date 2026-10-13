package dev.fajar.starter.expenses.presentation.overview

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache

internal fun ExpensesState.withCachedExpenses(
    result: AppResult<ExpenseSnapshot<ExpenseOverview>?>
): ExpensesState =
    when (result) {
        is AppResult.Failed ->
            copy(content = null, loading = false, cacheFailure = result.failure, dialog = null)
        is AppResult.Success -> {
            val snapshot = result.value
            val requested = selection
            val actual = snapshot?.value?.selection
            val matches =
                actual == null ||
                    requested == null ||
                    ((requested.employeeId == null || requested.employeeId == actual.employeeId) &&
                        (requested.month == null || requested.month == actual.month) &&
                        requested.status == actual.status &&
                        requested.after == actual.after &&
                        requested.employmentAfter == actual.employmentAfter)
            copy(
                content = snapshot?.takeIf { matches },
                loading = false,
                cacheFailure = null,
                selection = if (matches && actual != null) actual else selection,
            )
        }
    }

internal fun ExpensesState.withExpensesRefresh(
    result: AppResult<Unit>,
    requested: ExpenseSelection,
): ExpensesState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = content?.takeUnless { expenseFailureInvalidatesCache(result.failure) },
                dialog = dialog?.takeUnless { expenseFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
                selection = requested,
            )
    }
