package dev.fajar.starter.collaboration.domain.entities

/** Retained per-room delay; a different room's hint cannot turn this into an immediate retry. */
data class CollaborationSpaceIncomingRetry(
    val attemptedAtEpochMillis: Long,
    val eligibleAtEpochMillis: Long,
    val failures: Int,
) {
    override fun toString() = "CollaborationSpaceIncomingRetry([redacted])"
}
