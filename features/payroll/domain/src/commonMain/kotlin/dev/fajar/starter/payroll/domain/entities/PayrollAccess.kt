package dev.fajar.starter.payroll.domain.entities

import kotlin.time.Instant

data class PayrollAccess(
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
    override fun toString() = "PayrollAccess([redacted])"
}
