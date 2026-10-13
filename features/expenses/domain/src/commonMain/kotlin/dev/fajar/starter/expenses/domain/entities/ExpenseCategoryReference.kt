package dev.fajar.starter.expenses.domain.entities

data class ExpenseCategoryReference(
    val id: String,
    val code: String,
    val name: String,
    val version: Long,
    val revision: Long,
)
