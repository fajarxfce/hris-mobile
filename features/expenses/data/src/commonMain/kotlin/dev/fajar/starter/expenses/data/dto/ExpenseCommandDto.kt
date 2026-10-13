package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseCommandDto(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val claimId: String,
    val employeeId: String,
    val employeeNumber: String,
    val employmentAfter: String?,
    val timezone: String,
    val payload: ExpenseCommandPayloadDto,
    val reason: String,
    val createdAt: String,
)
