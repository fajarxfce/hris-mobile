package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*

fun availableExpenseActions(view: ExpenseView): Set<ExpenseRequestedAction> =
    ExpenseRequestedAction.entries.filterTo(linkedSetOf()) {
        admitExpenseAction(view, it) is AppResult.Success
    }

fun admitExpenseAction(view: ExpenseView, action: ExpenseRequestedAction): AppResult<Unit> {
    val claim = view.claim
    return when (action) {
        ExpenseRequestedAction.SUBMIT ->
            when {
                claim.status != ExpenseStatus.DRAFT ->
                    expenseFailure("expense_not_editable", FailureKind.Conflict)
                claim.submissionCount >= 20 ->
                    expenseFailure("expense_submission_limit", FailureKind.Conflict)
                claim.draft.lines.isEmpty() -> expenseFailure("expense_lines_required")
                else -> AppResult.Success(Unit)
            }
        ExpenseRequestedAction.CANCEL ->
            if (claim.status !in setOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED))
                expenseFailure("expense_not_editable", FailureKind.Conflict)
            else AppResult.Success(Unit)
        ExpenseRequestedAction.WITHDRAW -> {
            val current = view.submission
            if (
                claim.status != ExpenseStatus.PENDING ||
                    current == null ||
                    !current.current ||
                    claim.latestSubmissionId != current.id ||
                    current.claimId != claim.id ||
                    current.employeeId != claim.employeeId ||
                    current.claimVersion != claim.version ||
                    current.approval.status !in
                        setOf(ExpenseApprovalStatus.PENDING, ExpenseApprovalStatus.BLOCKED)
            )
                expenseFailure("expense_submission_not_current", FailureKind.Conflict)
            else AppResult.Success(Unit)
        }
    }
}
