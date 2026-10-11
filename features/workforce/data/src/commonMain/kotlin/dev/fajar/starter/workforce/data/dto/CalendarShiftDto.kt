package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CalendarShiftDto(
    val id: String,
    val revision: Long,
    val details: CalendarShiftDetailsDto,
)
