package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlinx.datetime.LocalDate

data class ExpenseLine(
    val id: String,
    val categoryId: String,
    val occurredOn: LocalDate,
    val amount: DecimalAmount,
    val description: String,
    val costCenterId: String?,
    val receiptIds: List<String>,
)
