package dev.fajar.starter.expenses.domain.entities

data class ExpenseReferenceValues(
    val employeeId: String,
    val categories: List<ExpenseLabelReference>,
    val costCenters: List<ExpenseLabelReference>,
    val receipts: List<ExpenseReceiptReference>,
) {
    override fun toString() = "ExpenseReferenceValues(redacted)"
}
