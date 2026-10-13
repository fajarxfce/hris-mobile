package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseSaveDraftRequest(
    val employmentId: String,
    val expectedVersion: Long?,
    val title: String,
    val description: String,
    val lines: List<ExpenseLineDto>,
    val reason: String,
)
