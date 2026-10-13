package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*

/** Exact IDR cents, bounded so a twenty-line claim cannot overflow a Long. */
fun expenseMinorUnits(amount: DecimalAmount): Long? {
    if (amount.isNegative) return null
    val whole = amount.value.substringBefore('.')
    val fraction = amount.value.substringAfter('.', "")
    if (whole.length > 14 || fraction.length > 2) return null
    return whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()
}

fun validateExpenseView(view: ExpenseView): AppResult<Unit> {
    val claim = view.claim
    val draft = claim.draft
    val total = expenseMinorUnits(draft.total)
    val amounts = draft.lines.map { expenseMinorUnits(it.amount) }
    if (
        draft.lines.size > 20 ||
            total == null ||
            amounts.any { it == null || it !in 1..99_999_999_999_999L } ||
            amounts.filterNotNull().sum() != total
    )
        return expenseFailure("client.invalid_response", FailureKind.Unexpected)
    if (view.history.items.any { it.version > claim.version })
        return expenseFailure("client.expense_changed", FailureKind.Conflict)
    if (view.historyAfter == null && view.history.items.firstOrNull()?.version != claim.version)
        return expenseFailure("client.expense_changed", FailureKind.Conflict)
    val submission = view.submission
    if (submission?.id != claim.latestSubmissionId)
        return expenseFailure("client.expense_changed", FailureKind.Conflict)
    if (submission != null) {
        if (submission.claimId != claim.id || submission.employeeId != claim.employeeId)
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
        if (submission.claimVersion != claim.version || submission.claimStatus != claim.status)
            return expenseFailure("client.expense_changed", FailureKind.Conflict)
        val submittedTotal = expenseMinorUnits(submission.total)
        val submittedAmounts = submission.lines.map { expenseMinorUnits(it.amount) }
        if (
            submission.lines.size !in 1..20 ||
                submittedTotal == null ||
                submittedAmounts.any { it == null || it !in 1..99_999_999_999_999L } ||
                submittedAmounts.filterNotNull().sum() != submittedTotal
        )
            return expenseFailure("client.invalid_response", FailureKind.Unexpected)
    }
    if (view.payments.isNotEmpty() && claim.status != ExpenseStatus.APPROVED)
        return expenseFailure("client.expense_changed", FailureKind.Conflict)
    return AppResult.Success(Unit)
}
