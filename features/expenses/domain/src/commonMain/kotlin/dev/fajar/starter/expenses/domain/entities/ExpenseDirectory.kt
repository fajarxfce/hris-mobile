package dev.fajar.starter.expenses.domain.entities

import kotlinx.datetime.LocalDate

data class ExpenseDirectory(
    val accountId: String,
    val companyId: String,
    val asOf: LocalDate,
    val timezone: String,
    val items: List<ExpenseEmployment>,
    val nextCursor: String?,
)
