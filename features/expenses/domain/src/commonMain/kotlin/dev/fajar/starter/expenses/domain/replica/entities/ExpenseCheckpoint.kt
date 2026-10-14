package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import kotlin.time.Instant

/** Acquisition is session-bound; the published generation belongs to the account. */
data class ExpenseCheckpoint(
    val access: ExpenseAccess,
    val generation: String,
    val revision: Long,
    val phase: ExpenseSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
) {
    override fun toString() = "ExpenseCheckpoint([redacted])"
}
