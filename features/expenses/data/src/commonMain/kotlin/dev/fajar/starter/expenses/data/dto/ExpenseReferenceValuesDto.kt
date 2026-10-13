package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseReferenceValuesDto(
    val employmentId: String,
    val categories: List<ExpenseLabelReferenceDto>,
    val costCenters: List<ExpenseLabelReferenceDto>,
    val receipts: List<ExpenseReceiptReferenceDto>,
)
