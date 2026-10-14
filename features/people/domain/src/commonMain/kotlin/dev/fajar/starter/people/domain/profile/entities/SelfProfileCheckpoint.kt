package dev.fajar.starter.people.domain.profile.entities

import kotlin.time.Instant

/** Acquisition is session-bound; the published generation belongs to the account. */
data class SelfProfileCheckpoint(
    val access: SelfProfileAccess,
    val generation: String,
    val revision: Long,
    val phase: SelfProfileSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
) {
    override fun toString() = "SelfProfileCheckpoint([redacted])"
}
