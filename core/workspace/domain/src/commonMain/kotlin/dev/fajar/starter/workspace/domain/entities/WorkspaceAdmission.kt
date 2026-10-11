package dev.fajar.starter.workspace.domain.entities

import kotlin.time.Instant

/** A bounded local admission based on authenticated account and company responses. */
data class WorkspaceAdmission(
    val accountId: String,
    val familyId: String,
    val checkedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "WorkspaceAdmission([redacted])"
}
