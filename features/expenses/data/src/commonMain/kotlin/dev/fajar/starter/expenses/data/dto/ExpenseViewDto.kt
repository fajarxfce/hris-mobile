package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseViewDto(
    val claim: ExpenseClaimDto,
    val submission: ExpenseSubmissionDto? = null,
    val history: ExpensePageDto<ExpenseChangeDto>,
    val historyAfter: Long? = null,
    val payments: List<ExpensePaymentAttemptDto>,
)
