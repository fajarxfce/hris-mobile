package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkCalendarDto(val scheduleVersion: Long? = null, val days: List<WorkDayDto>)
