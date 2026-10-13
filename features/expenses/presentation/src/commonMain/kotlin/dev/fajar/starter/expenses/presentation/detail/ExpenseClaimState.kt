package dev.fajar.starter.expenses.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes

data class ExpenseClaimState(
    val content: ExpenseSnapshot<ExpenseView>? = null,
    val historyAfter: Long? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
) {
    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::expenseFailureInvalidatesCache) == true ||
                refreshFailure?.let(::expenseFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            cacheFailure?.takeIf(::expenseFailureInvalidatesCache) ?: refreshFailure ?: cacheFailure

    val needsVerification: Boolean
        get() = failure?.code in expenseVerificationCodes

    val lines: List<ExpenseLineItem>
        get() = content?.value?.takeUnless { readRestricted }?.toLineItems().orEmpty()

    val nextHistoryAfter: Long?
        get() = content?.value?.history?.nextCursor?.toLongOrNull()
}
