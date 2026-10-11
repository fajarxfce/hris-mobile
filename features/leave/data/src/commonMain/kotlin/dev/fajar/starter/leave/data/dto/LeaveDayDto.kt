package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveDayDto(
    val workDate: String,
    val portion: String,
    val chargedDays: String,
    val startsAt: String,
    val endsAt: String,
    val plannedMinutes: Int,
    val chargedMinutes: Int,
)
