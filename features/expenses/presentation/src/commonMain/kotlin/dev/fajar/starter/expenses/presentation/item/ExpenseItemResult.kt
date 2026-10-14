package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingCopy

data class ExpenseItemResult(
    val line: ExpenseLine,
    val category: ExpenseLabelReference?,
    val costCenter: ExpenseLabelReference?,
    val receipts: List<ExpenseReceiptReference>,
    val copy: ExpenseWorkingCopy,
)
