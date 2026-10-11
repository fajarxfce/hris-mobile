package dev.fajar.starter.workforce.domain.attendance.entities

import kotlinx.datetime.LocalDate

data class AttendanceSelection(
    val employeeId: String? = null,
    val after: String? = null,
    val window: AttendanceWindow = AttendanceWindow.RECENT,
    val from: LocalDate? = null,
)
