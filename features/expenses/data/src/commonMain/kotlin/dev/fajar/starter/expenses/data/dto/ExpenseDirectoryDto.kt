package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseDirectoryDto(
    val accountId: String,
    val companyId: String,
    val asOf: String,
    val timezone: String,
    val employments: ExpensePageDto<ExpenseEmploymentDto>,
)
