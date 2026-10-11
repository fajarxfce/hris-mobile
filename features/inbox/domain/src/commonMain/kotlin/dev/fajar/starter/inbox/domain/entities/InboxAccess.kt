package dev.fajar.starter.inbox.domain.entities

import kotlin.time.Instant

/** Authorization snapshot, independently checked by each feature use case. */
data class InboxAccess(
    val sessionId: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val permissions: Set<String>,
    val sessionExpiresAt: Instant,
    val workspaceExpiresAt: Instant,
) {
    override fun toString() = "InboxAccess([redacted])"
}
