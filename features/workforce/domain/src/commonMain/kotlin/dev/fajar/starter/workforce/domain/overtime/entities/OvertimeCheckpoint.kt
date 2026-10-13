package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant

/** Acquisition progress is session-bound; published records belong to the account. */
data class OvertimeCheckpoint(
    val access: WorkforceAccess,
    val generation: String,
    val revision: Long,
    val phase: OvertimeSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
) {
    override fun toString() = "OvertimeCheckpoint([redacted])"
}

enum class OvertimeSyncPhase {
    BOOTSTRAP,
    CHANGES,
}
