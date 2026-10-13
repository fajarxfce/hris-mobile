package dev.fajar.starter.expenses.domain.entities

data class ExpenseReferenceQuery(
    val employeeId: String,
    val categoryIds: Set<String> = emptySet(),
    val costCenterIds: Set<String> = emptySet(),
    val receiptRevisionIds: Set<String> = emptySet(),
) {
    override fun toString() = "ExpenseReferenceQuery(redacted)"
}
