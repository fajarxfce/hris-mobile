@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.uuid.Uuid

const val EXPENSE_PAGE_SIZE = 20

fun expenseFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "The expense could not be loaded.", code = code))

fun expenseFailureInvalidatesCache(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in
            setOf(
                "expense_claim_not_found",
                "expense_submission_not_found",
                "employee_not_found",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )

fun validateExpenseId(id: String): AppResult<Unit> =
    if (id.length == 36 && Uuid.parseOrNull(id)?.toString() == id) AppResult.Success(Unit)
    else expenseFailure("expense_claim_not_found")

fun validateExpenseSelection(selection: ExpenseSelection): AppResult<Unit> {
    if (selection.month != null && selection.month.year !in 1900..2200)
        return expenseFailure("invalid_page")
    if (selection.employeeId != null && validateExpenseId(selection.employeeId) is AppResult.Failed)
        return expenseFailure("employee_not_found")
    val employmentAfter = selection.employmentAfter
    if (employmentAfter != null && (employmentAfter.isBlank() || employmentAfter.length > 32))
        return expenseFailure("invalid_page")
    if (
        selection.after != null &&
            (selection.month == null ||
                selection.employeeId == null ||
                validateExpenseId(selection.after) is AppResult.Failed)
    )
        return expenseFailure("invalid_page")
    return AppResult.Success(Unit)
}

fun validateExpenseHistoryCursor(after: Long?): AppResult<Unit> =
    if (after == null || after in 0..9_007_199_254_740_991L) AppResult.Success(Unit)
    else expenseFailure("invalid_page")
