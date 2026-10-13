package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseCostCenterDto(
    val id: String,
    val code: String,
    val name: String,
    val version: Long,
)
