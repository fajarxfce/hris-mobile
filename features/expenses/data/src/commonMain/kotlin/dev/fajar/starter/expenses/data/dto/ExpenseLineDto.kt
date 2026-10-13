package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseLineDto(
    val id: String,
    val categoryId: String,
    val occurredOn: String,
    val amount: String,
    val description: String,
    val costCenterId: String? = null,
    val receiptRevisionIds: List<String>,
)
