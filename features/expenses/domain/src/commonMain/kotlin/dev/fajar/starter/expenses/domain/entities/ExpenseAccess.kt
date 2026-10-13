package dev.fajar.starter.expenses.domain.entities

import kotlin.time.Instant

data class ExpenseAccess(
    val sessionId: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val timezone: String,
    val permissions: Set<String>,
    val sessionExpiresAt: Instant,
    val readUntil: Instant,
    val expiryCode: String,
) {
    override fun toString() = "ExpenseAccess([redacted])"
}
