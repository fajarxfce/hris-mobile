package dev.fajar.starter.expenses.presentation.draft

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.item.ExpenseItemResult

internal fun ExpenseDraftState.withPreparation(value: ExpenseDraftPreparation): ExpenseDraftState {
    if (command != null) return copy(preparation = value, restriction = null, failure = null)
    val updated = value.claim?.draft.toForm()
    val changed = form != null && version != value.claim?.version && dirty
    return if (form == null || (!dirty && version != value.claim?.version))
        copy(
            form = updated,
            original = updated,
            version = value.claim?.version,
            preparation = value,
            restriction = null,
            failure = null,
            remoteChanged = false,
        )
    else copy(preparation = value, restriction = null, failure = null, remoteChanged = changed)
}

internal fun ExpenseDraftState.withItem(value: ExpenseItemResult): ExpenseDraftState {
    val current = form ?: return this
    val lines =
        if (current.lines.any { it.id == value.line.id })
            current.lines.map { if (it.id == value.line.id) value.line else it }
        else current.lines + value.line
    val categories =
        (references?.categories.orEmpty().filterNot { it.id == value.category?.id } +
                listOfNotNull(value.category))
            .filter { reference -> lines.any { it.categoryId == reference.id } }
    val centers =
        (references?.costCenters.orEmpty().filterNot { it.id == value.costCenter?.id } +
                listOfNotNull(value.costCenter))
            .filter { reference -> lines.any { it.costCenterId == reference.id } }
    val receipts =
        (references?.receipts.orEmpty().filterNot { old ->
                value.receipts.any { it.revisionId == old.revisionId }
            } + value.receipts)
            .filter { reference -> lines.any { reference.revisionId in it.receiptIds } }
    return copy(
        form = current.copy(lines = lines),
        itemTarget = null,
        failure = null,
        references =
            preparation?.employee?.id?.let {
                ExpenseReferenceValues(it, categories, centers, receipts)
            },
    )
}
