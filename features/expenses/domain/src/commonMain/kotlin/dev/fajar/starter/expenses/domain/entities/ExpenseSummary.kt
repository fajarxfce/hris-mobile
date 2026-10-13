package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlin.time.Instant

data class ExpenseSummary(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val title: String,
    val total: DecimalAmount,
    val currency: String,
    val createdAt: Instant,
    val status: ExpenseStatus,
    val version: Long,
)
