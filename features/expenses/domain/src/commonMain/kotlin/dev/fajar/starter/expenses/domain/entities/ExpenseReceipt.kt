package dev.fajar.starter.expenses.domain.entities

data class ExpenseReceipt(
    val id: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val possibleDuplicate: Boolean,
)
