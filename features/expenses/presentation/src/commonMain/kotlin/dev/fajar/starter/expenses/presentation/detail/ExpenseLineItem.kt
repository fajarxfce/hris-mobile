package dev.fajar.starter.expenses.presentation.detail

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.expenses.domain.entities.ExpenseReceipt
import kotlinx.datetime.LocalDate

data class ExpenseLineItem(
    val id: String,
    val date: LocalDate,
    val amount: DecimalAmount,
    val description: String,
    val category: String?,
    val costCenter: String?,
    val receiptCount: Int,
    val receipts: List<ExpenseReceipt>,
)
