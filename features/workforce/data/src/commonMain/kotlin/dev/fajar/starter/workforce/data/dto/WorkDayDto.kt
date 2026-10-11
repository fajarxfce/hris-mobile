package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkDayDto(
    val workDate: String,
    val kind: String,
    val shift: CalendarShiftDto? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val plannedMinutes: Long = 0,
    val origin: String? = null,
    val originVersion: Long? = null,
    val holidayId: String? = null,
)
