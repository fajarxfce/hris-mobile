package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseReview(
    val claimVersion: Long,
    val approvalVersion: Long,
    val step: Int,
    val actorId: String,
    val decision: ExpenseDecision,
    val status: ExpenseStatus,
    val reason: String,
    val decidedAt: Instant,
)

enum class ExpenseDecision {
    APPROVE,
    REJECT,
    RETURN,
}
