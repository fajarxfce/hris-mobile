package dev.fajar.starter.expenses.domain.entities

/** Coherent claim facts; independently versioned reimbursement is acquired separately. */
data class ExpenseClaimRecord(
    val claim: ExpenseClaim,
    val submission: ExpenseSubmission?,
    val history: ExpensePage<ExpenseChange>,
    val historyAfter: Long? = null,
)
