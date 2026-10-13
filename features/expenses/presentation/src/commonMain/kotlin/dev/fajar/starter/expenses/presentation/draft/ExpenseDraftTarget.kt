package dev.fajar.starter.expenses.presentation.draft

data class ExpenseDraftTarget(
    val workspaceId: String,
    val employeeId: String,
    val claimId: String? = null,
    val employmentAfter: String? = null,
)
