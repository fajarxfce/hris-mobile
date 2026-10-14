package dev.fajar.starter.expenses.presentation.working

import dev.fajar.starter.expenses.domain.entities.*

/** Keeps display labels for completed lines without treating them as current picker authority. */
fun expenseWorkingReferences(
    employeeId: String,
    lines: List<ExpenseLine>,
    known: ExpenseReferenceValues?,
    category: ExpenseLabelReference? = null,
    center: ExpenseLabelReference? = null,
    receipts: List<ExpenseReceiptReference> = emptyList(),
) =
    ExpenseReferenceValues(
        employeeId,
        lines
            .map { it.categoryId }
            .distinct()
            .map { id ->
                category?.takeIf { it.id == id }
                    ?: known?.categories?.find { it.id == id }
                    ?: ExpenseLabelReference(id)
            },
        lines
            .mapNotNull { it.costCenterId }
            .distinct()
            .map { id ->
                center?.takeIf { it.id == id }
                    ?: known?.costCenters?.find { it.id == id }
                    ?: ExpenseLabelReference(id)
            },
        lines
            .flatMap { it.receiptIds }
            .distinct()
            .map { id ->
                receipts.find { it.revisionId == id }
                    ?: known?.receipts?.find { it.revisionId == id }
                    ?: ExpenseReceiptReference(id)
            },
    )
