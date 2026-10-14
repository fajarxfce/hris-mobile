package dev.fajar.starter.expenses.data.replica.dto

import dev.fajar.starter.expenses.data.dto.*
import kotlinx.serialization.Serializable

@Serializable
data class ExpenseHistoryPageDto(
    val claimId: String,
    val claimVersion: Long,
    val after: Long,
    val history: ExpensePageDto<ExpenseChangeDto>,
)
