package dev.fajar.starter.leave.domain.entities

data class LeaveDraftSelection(
    val employeeId: String,
    val typeId: String,
    val days: List<LeaveRequestedDay>,
)
