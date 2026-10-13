package dev.fajar.starter.expenses.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlin.time.Instant

data class ExpenseDraft(
    val revision: Int,
    val employeeNumber: String,
    val employeeName: String,
    val title: String,
    val description: String,
    val total: DecimalAmount,
    val currency: String,
    val lines: List<ExpenseLine>,
    val actorId: String,
    val reason: String,
    val recordedAt: Instant,
)
