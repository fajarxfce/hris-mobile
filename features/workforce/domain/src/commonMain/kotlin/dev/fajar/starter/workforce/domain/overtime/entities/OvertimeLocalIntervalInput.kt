package dev.fajar.starter.workforce.domain.overtime.entities

import kotlinx.datetime.LocalDateTime

/** Employee-entered wall times; their company zone is supplied by the admitted review. */
data class OvertimeLocalIntervalInput(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val breakMinutes: Int,
)
