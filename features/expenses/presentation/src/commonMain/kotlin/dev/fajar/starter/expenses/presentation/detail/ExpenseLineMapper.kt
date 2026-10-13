package dev.fajar.starter.expenses.presentation.detail

import dev.fajar.starter.expenses.domain.entities.ExpenseView

/** Frozen labels describe the displayed draft only when its submission revision matches. */
fun ExpenseView.toLineItems(): List<ExpenseLineItem> {
    val submitted =
        submission
            ?.takeIf { it.draftRevision == claim.draft.revision }
            ?.lines
            ?.associateBy { it.id }
            .orEmpty()
    return claim.draft.lines.map { line ->
        val published = submitted[line.id]
        ExpenseLineItem(
            line.id,
            line.occurredOn,
            line.amount,
            line.description,
            published?.category?.name,
            published?.costCenter?.name,
            published?.receipts?.size ?: line.receiptIds.size,
            published?.receipts.orEmpty(),
        )
    }
}
