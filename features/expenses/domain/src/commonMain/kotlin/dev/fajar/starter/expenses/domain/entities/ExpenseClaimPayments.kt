package dev.fajar.starter.expenses.domain.entities

/** Reimbursement progress advances independently of the approved claim revision. */
data class ExpenseClaimPayments(
    val claimId: String,
    val version: Long,
    val attempts: List<ExpensePaymentAttempt>,
)
