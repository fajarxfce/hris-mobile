package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** A read lease. Renewing it does not change the account/company/permission scope. */
data class LeaveAccess(
    val sessionId: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val permissions: Set<String>,
    val sessionExpiresAt: Instant,
    val readUntil: Instant,
    val expiryCode: String,
) {
    override fun toString() = "LeaveAccess([redacted])"
}
