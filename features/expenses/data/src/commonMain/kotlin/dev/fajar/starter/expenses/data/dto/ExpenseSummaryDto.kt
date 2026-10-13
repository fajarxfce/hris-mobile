package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseSummaryDto(
    val id: String,
    val employmentId: String,
    val employeeNumber: String,
    val employeeName: String,
    val title: String,
    val totalAmount: String,
    val currency: String,
    val createdAt: String,
    val status: String,
    val version: Long,
)
