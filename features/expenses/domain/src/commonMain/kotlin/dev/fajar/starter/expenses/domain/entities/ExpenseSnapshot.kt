package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseSnapshot<T>(
    val access: ExpenseAccess,
    val generation: String,
    val value: T,
    val fetchedAt: Instant,
    val refreshAfter: Instant,
) {
    override fun toString() = "ExpenseSnapshot([redacted])"
}
