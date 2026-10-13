package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseDraftDto(
    val revision: Int,
    val employeeNumber: String,
    val employeeName: String,
    val title: String,
    val description: String,
    val totalAmount: String,
    val currency: String,
    val lines: List<ExpenseLineDto>,
    val actorId: String,
    val reason: String,
    val recordedAt: String,
)
