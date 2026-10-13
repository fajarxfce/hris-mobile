package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.*

data class ExpenseItemTarget(
    val workspaceId: String,
    val preparation: ExpenseDraftPreparation,
    val line: ExpenseLine?,
    val references: ExpenseReferenceValues?,
)
