package dev.fajar.starter.expenses.data.working.dto

import dev.fajar.starter.expenses.data.dto.ExpenseLabelReferenceDto
import dev.fajar.starter.expenses.data.dto.ExpenseReceiptReferenceDto
import kotlinx.serialization.Serializable

@Serializable
data class ExpenseWorkingItemDto(
    val id: String,
    val categoryId: String,
    val occurredOn: String,
    val amount: String,
    val description: String,
    val costCenterId: String?,
    val receiptRevisionIds: List<String>,
    val category: ExpenseLabelReferenceDto?,
    val costCenter: ExpenseLabelReferenceDto?,
    val receipts: List<ExpenseReceiptReferenceDto>,
)
