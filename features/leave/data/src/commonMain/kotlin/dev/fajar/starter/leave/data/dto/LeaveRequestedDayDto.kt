package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable data class LeaveRequestedDayDto(val workDate: String, val portion: String)
