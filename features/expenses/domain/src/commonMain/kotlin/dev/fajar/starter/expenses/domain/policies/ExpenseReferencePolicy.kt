package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*

fun expenseReferenceQuery(
    employeeId: String,
    lines: List<ExpenseLine>,
): AppResult<ExpenseReferenceQuery> {
    if (lines.size > 20 || lines.any { it.receiptIds.size > 3 })
        return expenseFailure("invalid_expense_references")
    val query =
        ExpenseReferenceQuery(
            employeeId,
            lines.map { it.categoryId }.toSet(),
            lines.mapNotNull { it.costCenterId }.toSet(),
            lines.flatMap { it.receiptIds }.toSet(),
        )
    val valid = validateExpenseReferenceQuery(query)
    return if (valid is AppResult.Failed) valid else AppResult.Success(query)
}

fun validateExpenseReferenceQuery(query: ExpenseReferenceQuery): AppResult<Unit> =
    if (
        query.categoryIds.size > 20 ||
            query.costCenterIds.size > 20 ||
            query.receiptRevisionIds.size > 60 ||
            validateExpenseId(query.employeeId) is AppResult.Failed ||
            query.categoryIds.any { validateExpenseId(it) is AppResult.Failed } ||
            query.costCenterIds.any { validateExpenseId(it) is AppResult.Failed } ||
            query.receiptRevisionIds.any { validateExpenseId(it) is AppResult.Failed }
    )
        expenseFailure("invalid_expense_references")
    else AppResult.Success(Unit)

fun validateExpenseReferenceValues(
    query: ExpenseReferenceQuery,
    values: ExpenseReferenceValues,
): AppResult<Unit> =
    if (
        values.employeeId != query.employeeId ||
            values.categories.size != query.categoryIds.size ||
            values.categories.map { it.id }.toSet() != query.categoryIds ||
            values.costCenters.size != query.costCenterIds.size ||
            values.costCenters.map { it.id }.toSet() != query.costCenterIds ||
            values.receipts.size != query.receiptRevisionIds.size ||
            values.receipts.map { it.revisionId }.toSet() != query.receiptRevisionIds
    )
        expenseFailure("client.expense_reference_mismatch")
    else AppResult.Success(Unit)
