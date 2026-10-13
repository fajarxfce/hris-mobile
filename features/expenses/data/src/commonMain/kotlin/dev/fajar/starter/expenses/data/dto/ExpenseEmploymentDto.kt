package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseEmploymentDto(
    val id: String,
    val employeeNumber: String,
    val status: String,
    val version: Long,
)
