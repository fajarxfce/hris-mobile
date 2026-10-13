package dev.fajar.starter.expenses.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache

internal fun ExpenseClaimState.withCachedClaim(
    result: AppResult<ExpenseSnapshot<ExpenseView>?>
): ExpenseClaimState =
    when (result) {
        is AppResult.Failed -> copy(content = null, loading = false, cacheFailure = result.failure)
        is AppResult.Success -> {
            val snapshot = result.value
            copy(
                content =
                    when {
                        snapshot == null -> null
                        snapshot.value.historyAfter == historyAfter -> snapshot
                        else -> content
                    },
                loading = false,
                cacheFailure = null,
            )
        }
    }

internal fun ExpenseClaimState.withClaimRefresh(result: AppResult<Unit>): ExpenseClaimState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = content?.takeUnless { expenseFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
                exportFailure = null,
            )
    }
