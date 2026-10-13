package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.*
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
    val value = line ?: return ExpenseItemForm(preparation.asOf)
    return ExpenseItemForm(
        value.occurredOn,
        references?.categories?.find { it.id == value.categoryId }
            ?: ExpenseLabelReference(value.categoryId),
        value.amount.value,
        value.description,
        value.costCenterId?.let { id ->
            references?.costCenters?.find { it.id == id } ?: ExpenseLabelReference(id)
        },
        value.receiptIds.map { id ->
            references?.receipts?.find { it.revisionId == id } ?: ExpenseReceiptReference(id)
        },
    )
}
