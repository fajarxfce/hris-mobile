package dev.fajar.starter.expenses.domain.entities

data class ExpenseView(
    val claim: ExpenseClaim,
    val submission: ExpenseSubmission?,
    val history: ExpensePage<ExpenseChange>,
    val historyAfter: Long?,
    val payments: List<ExpensePaymentAttempt>,
)
