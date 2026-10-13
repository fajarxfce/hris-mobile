package dev.fajar.starter.expenses.presentation.action

data class ExpenseActionTarget(
    val workspaceId: String,
    val claimId: String,
    val mode: ExpenseActionMode,
    val employmentAfter: String? = null,
)
