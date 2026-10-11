package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeavePreviewRequest(
    val employeeId: String,
    val typeId: String,
    val days: List<LeaveRequestedDayDto>,
)
