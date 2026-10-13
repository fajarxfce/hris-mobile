package dev.fajar.starter.inbox.domain.entities

import kotlin.time.Instant

data class InboxCheckpoint(
    val access: InboxAccess,
    val generation: String,
    val revision: Long,
    val phase: InboxSyncPhase,
    val cursor: String?,
    val published: Boolean,
    val refreshedAt: Instant?,
    val refreshAfter: Instant,
    val pollAt: Instant?,
) {
    override fun toString() = "InboxCheckpoint([redacted])"
}

enum class InboxSyncPhase {
    BOOTSTRAP,
    CHANGES,
}
