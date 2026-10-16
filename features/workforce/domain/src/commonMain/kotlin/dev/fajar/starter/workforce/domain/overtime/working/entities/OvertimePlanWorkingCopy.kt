package dev.fajar.starter.workforce.domain.overtime.working.entities

import kotlin.time.Instant

/** One owned planning slot, independent of acquired requests and immutable commands. */
data class OvertimePlanWorkingCopy(
    val employeeId: String,
    val input: OvertimePlanWorkingInput,
    val revision: String,
    val savedAt: Instant,
) {
    override fun toString() = "OvertimePlanWorkingCopy([redacted])"
}
