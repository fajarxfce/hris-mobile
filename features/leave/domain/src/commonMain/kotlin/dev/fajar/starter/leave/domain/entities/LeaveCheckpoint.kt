package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** Progress belongs to an acquisition session; published facts belong to the account. */
data class LeaveCheckpoint(
    val access: LeaveAccess,
    val generation: String,
    val revision: Long,
    val phase: LeaveSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
    val recordSchema: Int = 2,
) {
    override fun toString() = "LeaveCheckpoint([redacted])"
}

enum class LeaveSyncPhase {
    BOOTSTRAP,
    CHANGES,
}
