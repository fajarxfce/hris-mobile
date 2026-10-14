package dev.fajar.starter.people.domain.replica.entities

import kotlin.time.Instant

/** Acquisition is session-bound; the published generation belongs to the account. */
data class DirectoryCheckpoint(
    val access: DirectoryAccess,
    val generation: String,
    val revision: Long,
    val phase: DirectorySyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
) {
    override fun toString() = "DirectoryCheckpoint([redacted])"
}
