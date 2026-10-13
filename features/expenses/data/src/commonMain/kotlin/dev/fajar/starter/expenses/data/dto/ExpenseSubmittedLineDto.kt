package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseSubmittedLineDto(
    val id: String,
    val occurredOn: String,
    val amount: String,
    val description: String,
    val category: ExpenseCategoryReferenceDto,
    val costCenter: ExpenseCostCenterDto? = null,
    val receipts: List<ExpenseReceiptDto>,
)
