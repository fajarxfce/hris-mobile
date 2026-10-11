package dev.fajar.starter.workforce.domain.entities

import kotlinx.datetime.LocalTime

/** The assigned immutable shift revision, independently of the current template. */
data class CalendarShift(
    val id: String,
    val revision: Long,
    val code: String,
    val name: String,
    val startsAt: LocalTime,
    val endsAt: LocalTime,
    val breakMinutes: Int,
    val timezone: String,
    val mode: CalendarWorkMode,
)

enum class CalendarWorkMode {
    ONSITE,
    REMOTE,
    FIELD,
}
