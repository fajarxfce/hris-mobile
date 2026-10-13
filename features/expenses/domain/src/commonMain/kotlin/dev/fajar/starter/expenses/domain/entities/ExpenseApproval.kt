package dev.fajar.starter.expenses.domain.entities

data class ExpenseApproval(
    val id: String,
    val status: ExpenseApprovalStatus,
    val version: Long,
    val currentStep: Int,
    val stages: List<Set<String>>,
)

enum class ExpenseApprovalStatus {
    PENDING,
    BLOCKED,
    APPROVED,
    REJECTED,
    CANCELLED,
}
