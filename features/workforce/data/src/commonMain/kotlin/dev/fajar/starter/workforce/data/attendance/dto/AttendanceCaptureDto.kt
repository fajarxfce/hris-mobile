package dev.fajar.starter.workforce.data.attendance.dto

import kotlinx.serialization.Serializable

@Serializable
data class AttendanceCaptureDto(
    val id: String,
    val workDate: String,
    val kind: String,
    val capturedAt: String,
    val deviceId: String,
    val windowId: String?,
    val offline: Boolean,
    val location: AttendanceLocationDto?,
)

@Serializable
data class AttendanceLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val mocked: Boolean,
)

@Serializable
data class AttendanceCommandDto(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val employeeId: String,
    val capture: AttendanceCaptureDto,
    val locationObservedAt: String?,
)
