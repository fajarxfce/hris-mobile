package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.ExpenseLineInput

internal fun ExpenseItemForm.toInput(id: String? = null) =
    ExpenseLineInput(
        id,
        category?.id.orEmpty(),
        date.toString(),
        amount.replace(',', '.'),
        description,
        costCenter?.id,
        receipts.map { it.revisionId },
    )
