package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpensePaymentAttempt(
    val batchId: String,
    val itemId: String,
    val status: ExpensePaymentStatus,
    val createdAt: Instant,
    val releasedAt: Instant?,
    val settledAt: Instant?,
)

enum class ExpensePaymentStatus {
    PREPARED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}
