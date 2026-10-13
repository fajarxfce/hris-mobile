package dev.fajar.starter.leave.domain.entities

import dev.fajar.starter.sync.domain.entities.SyncReference
import kotlin.time.Instant

/** Null is an explicit feed tombstone, never a failed canonical read. */
data class LeaveReplicaEntry(
    val reference: SyncReference,
    val record: LeaveReplicaRecord?,
    val acquiredAt: Instant? = null,
) {
    override fun toString() = "LeaveReplicaEntry([redacted])"
}
