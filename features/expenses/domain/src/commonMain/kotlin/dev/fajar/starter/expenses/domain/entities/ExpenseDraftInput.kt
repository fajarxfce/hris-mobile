package dev.fajar.starter.expenses.domain.entities

data class ExpenseDraftInput(
    val title: String,
    val description: String,
    val lines: List<ExpenseLine>,
    val reason: String,
) {
    override fun toString() = "ExpenseDraftInput([redacted])"
}
