package dev.fajar.starter.expenses.domain.entities

enum class ExpenseCommandAdmission {
    STORED,
    OCCUPIED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}
