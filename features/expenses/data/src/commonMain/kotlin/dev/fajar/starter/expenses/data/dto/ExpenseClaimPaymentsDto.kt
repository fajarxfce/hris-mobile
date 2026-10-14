package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseClaimPaymentsDto(
    val claimId: String,
    val version: Long,
    val attempts: List<ExpensePaymentAttemptDto>,
)
