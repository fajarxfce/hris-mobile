package dev.fajar.starter.workforce.data.attendance.dto

import dev.fajar.starter.workforce.data.dto.*
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceSnapshotDto(
    val directory: WorkforceDirectoryDto,
    val employeeId: String?,
    val from: String,
    val until: String,
    val calendar: WorkCalendarDto?,
    val days: List<AttendanceDayDto>,
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val fetchedAt: String,
    val expiresAt: String,
    val after: String? = null,
)
