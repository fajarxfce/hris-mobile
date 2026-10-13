package dev.fajar.starter.expenses.domain.entities

data class ExpenseCommandRecord(
    val command: ExpenseCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: ExpenseCommandOutcome,
)
