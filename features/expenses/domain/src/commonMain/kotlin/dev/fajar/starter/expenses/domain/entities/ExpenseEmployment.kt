package dev.fajar.starter.expenses.domain.entities

data class ExpenseEmployment(
    val id: String,
    val employeeNumber: String,
    val status: ExpenseEmploymentStatus,
    val version: Long,
)

enum class ExpenseEmploymentStatus {
    ACTIVE,
    PROBATION,
    SUSPENDED,
    ENDED,
}
