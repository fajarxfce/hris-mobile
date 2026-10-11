package dev.fajar.starter.workforce.data.attendance.dto

import dev.fajar.starter.workforce.data.dto.WorkDayDto
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceEntryDto(
    val id: String,
    val kind: String,
    val capturedAt: String,
    val receivedAt: String,
    val offline: Boolean,
    val status: String,
    val issues: Set<String>,
    val schedule: WorkDayDto,
    val version: Long,
)

@Serializable
data class AttendanceDayDto(
    val workDate: String,
    val entries: List<AttendanceEntryDto>,
    val acceptedMinutes: Long,
    val pendingCount: Int,
    val incomplete: Boolean,
    val correction: AttendanceCorrectionDto?,
)

@Serializable
data class AttendanceCorrectionDto(
    val id: String,
    val clockIn: String?,
    val clockOut: String?,
    val version: Long,
)

@Serializable data class AttendanceReceiptDto(val id: String, val version: Long)

@Serializable
data class AttendanceCaptureWindowDto(
    val id: String,
    val employeeId: String,
    val deviceId: String,
    val issuedAt: String,
    val expiresAt: String,
)

@Serializable data class AttendanceWindowRequest(val deviceId: String)
