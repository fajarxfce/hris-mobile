package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseOverviewDto(
    val directory: ExpenseDirectoryDto,
    val employeeId: String? = null,
    val month: String,
    val employmentAfter: String? = null,
    val status: String? = null,
    val after: String? = null,
    val page: ExpensePageDto<ExpenseSummaryDto>,
)
