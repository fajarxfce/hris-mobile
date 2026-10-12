package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant

/** One bounded read view. Its lease cannot be extended by decoding old storage. */
data class OvertimeSnapshot<T>(
    val access: WorkforceAccess,
    val generation: String,
    val value: T,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
