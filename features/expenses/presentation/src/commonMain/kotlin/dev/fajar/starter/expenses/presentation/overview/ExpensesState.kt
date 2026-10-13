package dev.fajar.starter.expenses.presentation.overview

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes

data class ExpensesState(
    val selection: ExpenseSelection? = null,
    val content: ExpenseSnapshot<ExpenseOverview>? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val dialog: ExpenseFilter? = null,
    val pickerYear: Int? = null,
) {
    val canCreate: Boolean
        get() =
            !readRestricted &&
                content?.value?.selection?.employeeId != null &&
                content?.access?.permissions?.any {
                    it in setOf("expenses.self.manage", "expenses.manage")
                } == true

    val filters: ExpenseSelection?
        get() = selection ?: content?.value?.selection

    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::expenseFailureInvalidatesCache) == true ||
                refreshFailure?.let(::expenseFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            cacheFailure?.takeIf(::expenseFailureInvalidatesCache) ?: refreshFailure ?: cacheFailure

    val needsVerification: Boolean
        get() = failure?.code in expenseVerificationCodes
}

enum class ExpenseFilter {
    MONTH,
    STATUS,
    EMPLOYMENT,
}
