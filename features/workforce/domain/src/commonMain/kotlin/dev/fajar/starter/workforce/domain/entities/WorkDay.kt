package dev.fajar.starter.workforce.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

sealed interface WorkDay {
    val date: LocalDate

    data class Work(
        override val date: LocalDate,
        val shift: CalendarShift,
        val startsAt: Instant,
        val endsAt: Instant,
        val plannedMinutes: Long,
        val origin: CalendarOrigin,
        val originVersion: Long,
    ) : WorkDay

    data class Off(
        override val date: LocalDate,
        val origin: CalendarOrigin,
        val originVersion: Long,
        val holidayId: String?,
    ) : WorkDay

    data class Unassigned(override val date: LocalDate) : WorkDay
}

enum class CalendarOrigin {
    PATTERN,
    ROSTER,
    HOLIDAY,
}
