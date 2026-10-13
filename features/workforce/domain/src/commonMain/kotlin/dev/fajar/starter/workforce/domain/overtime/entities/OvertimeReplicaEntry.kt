package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.sync.domain.entities.SyncReference
import kotlin.time.Instant

/** A null record represents an explicit feed deletion, never a failed read. */
data class OvertimeReplicaEntry(
    val reference: SyncReference,
    val record: OvertimeDetails?,
    val acquiredAt: Instant? = null,
) {
    override fun toString() = "OvertimeReplicaEntry([redacted])"
}
