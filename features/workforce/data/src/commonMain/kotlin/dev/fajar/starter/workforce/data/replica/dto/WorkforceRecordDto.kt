package dev.fajar.starter.workforce.data.replica.dto

import dev.fajar.starter.workforce.data.attendance.dto.AttendanceDayDto
import dev.fajar.starter.workforce.data.dto.CalendarShiftDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkforceRecordDto {
    val id: String
    val version: Long
}

@Serializable
@SerialName("attendance")
data class AttendanceRecordDto(
    override val id: String,
    override val version: Long,
    val employeeId: String,
    val day: AttendanceDayDto,
) : WorkforceRecordDto

@Serializable
@SerialName("schedule")
data class WorkScheduleRecordDto(
    override val id: String,
    override val version: Long,
    val employeeId: String,
    val assignment: WorkScheduleAssignmentDto,
) : WorkforceRecordDto

@Serializable
data class WorkScheduleAssignmentDto(
    val effectiveFrom: String,
    val revision: Long,
    val days: Map<String, CalendarShiftDto>,
)

@Serializable
@SerialName("roster")
data class RosterRecordDto(
    override val id: String,
    override val version: Long,
    val employeeId: String,
    val workDate: String,
    val shift: CalendarShiftDto?,
) : WorkforceRecordDto

@Serializable
@SerialName("holiday")
data class WorkHolidayRecordDto(
    override val id: String,
    override val version: Long,
    val workDate: String,
    val name: String,
    val active: Boolean,
) : WorkforceRecordDto
