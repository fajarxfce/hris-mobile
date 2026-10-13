package dev.fajar.starter.workspace.domain.entities

import kotlin.time.Instant

/** Recent authority for preparing operations; its deadline does not expire local reads. */
data class WorkspaceAdmission(
    val accountId: String,
    val familyId: String,
    val checkedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "WorkspaceAdmission([redacted])"
}
