package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlinx.datetime.LocalDate

data class ExpenseSubmittedLine(
    val id: String,
    val occurredOn: LocalDate,
    val amount: DecimalAmount,
    val description: String,
    val category: ExpenseCategoryReference,
    val costCenter: ExpenseCostCenter?,
    val receipts: List<ExpenseReceipt>,
)
