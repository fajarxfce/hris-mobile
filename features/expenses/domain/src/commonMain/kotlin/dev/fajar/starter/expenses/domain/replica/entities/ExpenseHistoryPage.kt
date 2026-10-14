package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.expenses.domain.entities.*

data class ExpenseHistoryPage(
    val claimId: String,
    val claimVersion: Long,
    val after: Long,
    val history: ExpensePage<ExpenseChange>,
)
