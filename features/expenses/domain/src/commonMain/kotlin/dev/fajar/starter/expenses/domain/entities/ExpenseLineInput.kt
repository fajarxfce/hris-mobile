package dev.fajar.starter.expenses.domain.entities

data class ExpenseLineInput(
    val id: String? = null,
    val categoryId: String,
    val occurredOn: String,
    val amount: String,
    val description: String,
    val costCenterId: String? = null,
    val receiptRevisionIds: List<String> = emptyList(),
) {
    override fun toString() = "ExpenseLineInput([redacted])"
}
