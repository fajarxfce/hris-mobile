package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.expenses.domain.entities.*

data class ExpenseReplicaPage(
    val checkpoint: ExpenseCheckpoint?,
    val page: ExpensePage<ExpenseSummary>,
)
