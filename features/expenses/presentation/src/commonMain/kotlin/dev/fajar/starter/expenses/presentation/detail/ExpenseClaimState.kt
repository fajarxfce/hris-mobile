package dev.fajar.starter.expenses.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.admitExpenseEditing
import dev.fajar.starter.expenses.domain.policies.availableExpenseActions
import dev.fajar.starter.expenses.domain.policies.expenseFailureInvalidatesCache
import dev.fajar.starter.expenses.presentation.action.ExpenseActionMode
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes

data class ExpenseClaimState(
    val command: ExpenseCommandRecord? = null,
    val commandReady: Boolean = false,
    val commandFailure: Failure? = null,
    val content: ExpenseSnapshot<ExpenseView>? = null,
    val historyAfter: Long? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
) {
    val actionOptions: Set<ExpenseActionMode>
        get() =
            content
                ?.takeUnless { readRestricted }
                ?.let { snapshot ->
                    if (admitExpenseEditing(snapshot.access) is AppResult.Failed) emptySet()
                    else
                        ExpenseActionMode.entries.filterTo(linkedSetOf()) {
                            it.action in availableExpenseActions(snapshot.value)
                        }
                }
                .orEmpty()

    val canOpenAction
        get() =
            !readRestricted &&
                !refreshing &&
                commandReady &&
                commandFailure == null &&
                command == null

    val canRecover
        get() = !readRestricted && !refreshing && commandReady && command != null

    val canEdit: Boolean
        get() =
            canOpenAction &&
                content?.value?.claim?.status in
                    setOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED) &&
                content?.access?.permissions?.any {
                    it in setOf("expenses.self.manage", "expenses.manage")
                } == true

    val readRestricted: Boolean
        get() =
            commandFailure?.let(::expenseFailureInvalidatesCache) == true ||
                cacheFailure?.let(::expenseFailureInvalidatesCache) == true ||
                refreshFailure?.let(::expenseFailureInvalidatesCache) == true

    val failure: Failure?
        get() =
            commandFailure?.takeIf(::expenseFailureInvalidatesCache)
                ?: cacheFailure?.takeIf(::expenseFailureInvalidatesCache)
                ?: refreshFailure
                ?: commandFailure
                ?: cacheFailure

    val needsVerification: Boolean
        get() = failure?.code in expenseVerificationCodes

    val lines: List<ExpenseLineItem>
        get() = content?.value?.takeUnless { readRestricted }?.toLineItems().orEmpty()

    val nextHistoryAfter: Long?
        get() = content?.value?.history?.nextCursor?.toLongOrNull()
}
