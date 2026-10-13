package dev.fajar.starter.workforce.domain.entities

import kotlin.time.Instant

/** Local owner and permissions, with a separate deadline for preparing authorized actions. */
data class WorkforceAccess(
    val sessionId: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val permissions: Set<String>,
    val sessionExpiresAt: Instant,
    val authorizationExpiresAt: Instant,
    val authorizationExpiryCode: String,
) {
    override fun toString() = "WorkforceAccess([redacted])"
}
