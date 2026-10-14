package dev.fajar.starter.expenses.domain.replica.entities

data class ExpenseHistoryWork(
    val claimId: String,
    val claimVersion: Long,
    val acquisitionId: String,
    val after: Long,
)
