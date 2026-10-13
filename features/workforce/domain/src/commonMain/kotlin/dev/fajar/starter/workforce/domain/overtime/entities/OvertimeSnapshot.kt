package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant

/** One stored read view. Freshness and command authorization are separate from local access. */
data class OvertimeSnapshot<T>(
    val access: WorkforceAccess,
    val generation: String,
    val value: T,
    val fetchedAt: Instant,
    val refreshAfter: Instant,
)
