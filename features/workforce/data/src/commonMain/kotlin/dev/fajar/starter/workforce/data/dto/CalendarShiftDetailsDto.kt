package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CalendarShiftDetailsDto(
    val code: String,
    val name: String,
    val startsAt: String,
    val endsAt: String,
    val breakMinutes: Int,
    val timezone: String,
    val mode: String,
)
