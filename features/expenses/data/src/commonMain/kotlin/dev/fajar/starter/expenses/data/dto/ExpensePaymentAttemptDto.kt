package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpensePaymentAttemptDto(
    val batchId: String,
    val itemId: String,
    val status: String,
    val createdAt: String,
    val releasedAt: String? = null,
    val settledAt: String? = null,
)
