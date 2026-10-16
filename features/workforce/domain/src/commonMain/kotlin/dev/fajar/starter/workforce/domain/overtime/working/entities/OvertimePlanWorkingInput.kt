package dev.fajar.starter.workforce.domain.overtime.working.entities

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Unfinished local input; a fresh schedule review separately authorizes submission. */
data class OvertimePlanWorkingInput(
    val workDate: LocalDate,
    val startDate: LocalDate = workDate,
    val endDate: LocalDate = workDate,
    val startsAt: LocalTime? = null,
    val endsAt: LocalTime? = null,
    val breakMinutes: String = "0",
    val reason: String = "",
) {
    override fun toString() = "OvertimePlanWorkingInput([redacted])"
}
