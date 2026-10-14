package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant

/** Acquisition is session-bound; the published generation belongs to the account. */
data class WorkforceCheckpoint(
    val access: WorkforceAccess,
    val generation: String,
    val revision: Long,
    val phase: WorkforceSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
) {
    override fun toString() = "WorkforceCheckpoint([redacted])"
}

enum class WorkforceSyncPhase {
    BOOTSTRAP,
    CHANGES,
}
