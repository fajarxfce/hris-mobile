package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.*

data class ExpenseItemResult(
    val line: ExpenseLine,
    val category: ExpenseLabelReference?,
    val costCenter: ExpenseLabelReference?,
    val receipts: List<ExpenseReceiptReference>,
)
