package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseClaimRecordDto(
    val claim: ExpenseClaimDto,
    val submission: ExpenseSubmissionDto?,
    val history: ExpensePageDto<ExpenseChangeDto>,
)
