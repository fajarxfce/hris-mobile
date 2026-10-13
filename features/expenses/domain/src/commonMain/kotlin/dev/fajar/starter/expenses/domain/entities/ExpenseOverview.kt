package dev.fajar.starter.expenses.domain.entities

data class ExpenseOverview(
    val directory: ExpenseDirectory,
    val selection: ExpenseSelection,
    val page: ExpensePage<ExpenseSummary>,
)
