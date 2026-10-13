package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseSubmitRequest(
    val submissionId: String,
    val expectedVersion: Long,
    val reason: String,
)
