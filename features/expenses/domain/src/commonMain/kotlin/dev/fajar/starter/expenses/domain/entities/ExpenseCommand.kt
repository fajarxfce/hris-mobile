package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

/** Immutable confirmed input. Credentials are never stored with the operation. */
data class ExpenseCommand(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val claimId: String,
    val employeeId: String,
    val employeeNumber: String,
    val employmentAfter: String?,
    val timezone: String,
    val payload: ExpenseCommandPayload,
    val reason: String,
    val createdAt: Instant,
) {
    override fun toString() = "ExpenseCommand([redacted])"
}
