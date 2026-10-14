package dev.fajar.starter.expenses.domain.working.entities

import dev.fajar.starter.expenses.domain.entities.ExpenseLabelReference
import dev.fajar.starter.expenses.domain.entities.ExpenseLineInput
import dev.fajar.starter.expenses.domain.entities.ExpenseReceiptReference

/**
 * An unfinished item may retain incomplete amounts and references without becoming a claim line.
 */
data class ExpenseWorkingItem(
    val input: ExpenseLineInput,
    val category: ExpenseLabelReference? = null,
    val costCenter: ExpenseLabelReference? = null,
    val receipts: List<ExpenseReceiptReference> = emptyList(),
) {
    override fun toString() = "ExpenseWorkingItem([redacted])"
}
