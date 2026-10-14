package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingItem
import kotlinx.datetime.LocalDate

data class ExpenseItemForm(
    val date: LocalDate,
    val category: ExpenseLabelReference? = null,
    val amount: String = "",
    val description: String = "",
    val costCenter: ExpenseLabelReference? = null,
    val receipts: List<ExpenseReceiptReference> = emptyList(),
) {
    override fun toString() = "ExpenseItemForm(redacted)"
}

internal fun ExpenseItemTarget.initialForm(): ExpenseItemForm {
    return requireNotNull(copy.item).toForm()
}

internal fun ExpenseWorkingItem.toForm(): ExpenseItemForm {
    val value = input
    return ExpenseItemForm(
        LocalDate.parse(value.occurredOn),
        category ?: value.categoryId.takeIf { it.isNotEmpty() }?.let { ExpenseLabelReference(it) },
        value.amount,
        value.description,
        value.costCenterId?.let { id -> costCenter ?: ExpenseLabelReference(id) },
        value.receiptRevisionIds.map { id ->
            receipts.find { it.revisionId == id } ?: ExpenseReceiptReference(id)
        },
    )
}
